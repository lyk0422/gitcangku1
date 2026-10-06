package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.GroupQuotaResponse;
import com.example.starter.exposure.web.IdentityGroupResponse;
import com.example.starter.exposure.web.MergeIdentityRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份归并 H2（MODE=MySQL）集成测试：归并主流程、组额度共享、历史归属、
 * 版本冲突、成员上限、幂等与 HTTP 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityMergeIntegrationTest {

    /** 可控时钟：固定起点，可按毫秒推进。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        void advanceMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final Instant BASE = Instant.parse("2026-09-22T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    /** 以可控时钟覆盖生产系统时钟。 */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MockMvc mockMvc;
    @Autowired
    Clock clock;

    private MutableClock mutableClock() {
        return (MutableClock) clock;
    }

    @BeforeEach
    void cleanAndReset() {
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM visitor_identity");
        jdbc.update("DELETE FROM identity_group");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private IdentityGroupResponse merge(String requestId, String a, String b, int expectedVersion) {
        return service.mergeIdentities(new MergeIdentityRequest(requestId, a, b, "tester", expectedVersion));
    }

    @Test
    @DisplayName("题干主场景：上限2，A、B各确认1次，归并后两编号新申请都429，历史仍归属原编号")
    void mergeThenGroupCapShared_andHistoryKept() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 2));
        ReservationResponse ra = service.apply(new ApplyExposureRequest("req-a1", "g-c1", "ga"));
        service.confirm(ra.reservationId(), new ReservationActionRequest("req-ok-a"));
        ReservationResponse rb = service.apply(new ApplyExposureRequest("req-a2", "g-c1", "gb"));
        service.confirm(rb.reservationId(), new ReservationActionRequest("req-ok-b"));

        IdentityGroupResponse group = merge("req-m1", "ga", "gb", 0);
        assertEquals(1, group.version());
        assertEquals(List.of("ga", "gb"), group.members());

        // 组内合计 2 已达原每访客上限：两编号新申请都 429
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "g-c1", "ga")));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a4", "g-c1", "gb")));

        // 历史确认记录仍归属原 A、B，申请日不变
        ReservationResponse detailA = service.getReservation(ra.reservationId());
        assertEquals("ga", detailA.visitorId());
        assertEquals(DAY, detailA.utcDate());
        assertEquals("CONFIRMED", detailA.status().name());
        assertEquals(1, service.queryQuota("g-c1", "ga", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("g-c1", "gb", DAY).usedVisitor());

        // 公告总额度仍按原计数
        QuotaResponse total = service.queryQuota("g-c1", null, DAY);
        assertEquals(2, total.usedTotal());

        // 组查询：成员、合计及原编号分账
        GroupQuotaResponse gq = service.queryGroupQuota("ga", "g-c1", DAY);
        assertEquals(group.groupId(), gq.groupId());
        assertEquals(List.of("ga", "gb"), gq.members());
        assertEquals(2, gq.perVisitorDailyCap());
        assertEquals(2, gq.usedGroup());
        assertEquals(0, gq.remainingGroup());
        assertEquals(2, gq.breakdown().size());
        assertEquals("ga", gq.breakdown().get(0).visitorId());
        assertEquals(1, gq.breakdown().get(0).usedVisitor());
        assertEquals("gb", gq.breakdown().get(1).visitorId());
        assertEquals(1, gq.breakdown().get(1).usedVisitor());
    }

    @Test
    @DisplayName("归并后合计未满可继续申请，预占保留最初申请编号；合计满后组内任何编号都 429")
    void groupSumSharedAcrossMembers() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 2));
        ReservationResponse ra = service.apply(new ApplyExposureRequest("req-a1", "g-c1", "ga"));
        service.confirm(ra.reservationId(), new ReservationActionRequest("req-ok-a"));

        merge("req-m1", "ga", "gb", 0);

        // 匿名访客登录后换新编号 gb：与 ga 共享后续曝光限制
        ReservationResponse rb = service.apply(new ApplyExposureRequest("req-a2", "g-c1", "gb"));
        assertEquals("gb", rb.visitorId());
        assertEquals(2, service.queryGroupQuota("ga", "g-c1", DAY).usedGroup());

        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "g-c1", "gb")));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a4", "g-c1", "ga")));
        // ga 自己只确认 1 次，但组账已满
        assertEquals(1, service.queryQuota("g-c1", "ga", DAY).usedVisitor());
    }

    @Test
    @DisplayName("归并时合计超限仍生效且禁止新增；取消释放后合计回落可再次申请")
    void mergeOverCapStillEffective_releaseReenablesApply() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 2));
        ReservationResponse ra1 = service.apply(new ApplyExposureRequest("req-a1", "g-c1", "ga"));
        ReservationResponse ra2 = service.apply(new ApplyExposureRequest("req-a2", "g-c1", "ga"));
        service.apply(new ApplyExposureRequest("req-a3", "g-c1", "gb"));

        // 归并时合计 3 已超上限 2：归并仍生效，不取消既有预占、额度不变负
        IdentityGroupResponse group = merge("req-m1", "ga", "gb", 0);
        assertEquals(2, group.members().size());
        GroupQuotaResponse over = service.queryGroupQuota("ga", "g-c1", DAY);
        assertEquals(3, over.usedGroup());
        assertEquals(0, over.remainingGroup());

        assert429(() -> service.apply(new ApplyExposureRequest("req-a4", "g-c1", "ga")));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a5", "g-c1", "gb")));

        // 取消只释放原预占一次：合计 3 -> 2，仍满
        service.cancel(ra1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(2, service.queryGroupQuota("ga", "g-c1", DAY).usedGroup());
        assert429(() -> service.apply(new ApplyExposureRequest("req-a6", "g-c1", "gb")));

        // 再取消一次：合计回落到 1，可再次申请
        service.cancel(ra2.reservationId(), new ReservationActionRequest("req-x2"));
        ReservationResponse again = service.apply(new ApplyExposureRequest("req-a7", "g-c1", "gb"));
        assertEquals("RESERVED", again.status().name());
        assertEquals(2, service.queryGroupQuota("ga", "g-c1", DAY).usedGroup());
    }

    @Test
    @DisplayName("过期只释放原预占一次：组内合计回落后可再次申请")
    void expiryReleasesGroupSum() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 2));
        service.apply(new ApplyExposureRequest("req-a1", "g-c1", "ga"));
        service.apply(new ApplyExposureRequest("req-a2", "g-c1", "gb"));
        merge("req-m1", "ga", "gb", 0);
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "g-c1", "ga")));

        mutableClock().advanceMillis(60_000L);

        ReservationResponse again = service.apply(new ApplyExposureRequest("req-a4", "g-c1", "ga"));
        assertEquals("RESERVED", again.status().name());
        GroupQuotaResponse gq = service.queryGroupQuota("gb", "g-c1", DAY);
        assertEquals(1, gq.usedGroup());
        assertEquals(1, gq.breakdown().get(0).usedVisitor());
        assertEquals(0, gq.breakdown().get(1).usedVisitor());
    }

    @Test
    @DisplayName("归并幂等：同键同参（含方向互换）重放首次响应；异参 409；失败不占键")
    void mergeIdempotency_replayConflictAndFailureNotOccupied() {
        IdentityGroupResponse first = merge("key-m1", "ga", "gb", 0);

        IdentityGroupResponse replay = merge("key-m1", "ga", "gb", 0);
        assertEquals(first.groupId(), replay.groupId());
        assertEquals(1, replay.version());

        // 编号对无方向：同键换序同参仍重放首次响应
        IdentityGroupResponse swapped = service.mergeIdentities(
                new MergeIdentityRequest("key-m1", "gb", "ga", "tester", 0));
        assertEquals(first.groupId(), swapped.groupId());

        // 同键异参：操作者不同或期望版本不同都 409
        assert409(() -> service.mergeIdentities(
                new MergeIdentityRequest("key-m1", "ga", "gb", "other", 0)));
        assert409(() -> service.mergeIdentities(
                new MergeIdentityRequest("key-m1", "ga", "gb", "tester", 1)));

        // 失败的归并（期望版本不匹配）不占键：同键修正参数后可成功
        assert409(() -> merge("key-m2", "ga", "gc", 5));
        IdentityGroupResponse retried = merge("key-m2", "ga", "gc", 1);
        assertEquals(first.groupId(), retried.groupId());
        assertEquals(2, retried.version());
        assertEquals(List.of("ga", "gb", "gc"), retried.members());
    }

    @Test
    @DisplayName("已在一组的重复归并不新增成员、版本不变；期望版本不匹配 409")
    void repeatedMergeDoesNotAddMembers() {
        IdentityGroupResponse group = merge("req-m1", "ga", "gb", 0);
        assertEquals(1, group.version());

        IdentityGroupResponse again = merge("req-m2", "gb", "ga", 1);
        assertEquals(group.groupId(), again.groupId());
        assertEquals(1, again.version());
        assertEquals(List.of("ga", "gb"), again.members());

        assert409(() -> merge("req-m3", "ga", "gb", 0));
    }

    @Test
    @DisplayName("组最多 10 个编号：第 11 个编号归并返回 409 且失败不占幂等键")
    void groupMemberLimit10() {
        for (int i = 1; i <= 9; i++) {
            IdentityGroupResponse group = merge("req-m" + i, "u0", "u" + i, i - 1);
            assertEquals(i, group.version());
            assertEquals(i + 1, group.members().size());
        }
        assert409(() -> merge("req-m10", "u0", "u10", 9));
        // 失败不占键：同键重试仍 409；另一对未归并编号以正确版本可成功
        assert409(() -> merge("req-m10", "u0", "u10", 9));
        IdentityGroupResponse pair = merge("req-p1", "v0", "v1", 0);
        assertEquals(1, pair.version());
    }

    @Test
    @DisplayName("两个已归并组再合并：期望版本为两组版本之和，成员全部并入")
    void mergeTwoGroups() {
        IdentityGroupResponse g1 = merge("req-m1", "ga", "gb", 0);
        IdentityGroupResponse g2 = merge("req-m2", "gc", "gd", 0);
        assertTrue(!g1.groupId().equals(g2.groupId()));

        IdentityGroupResponse merged = merge("req-m3", "ga", "gc", 2);
        assertEquals(3, merged.version());
        assertEquals(List.of("ga", "gb", "gc", "gd"), merged.members());

        // 组内任意编号查询都看到同一组
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 4));
        GroupQuotaResponse viaD = service.queryGroupQuota("gd", "g-c1", DAY);
        assertEquals(merged.groupId(), viaD.groupId());
        assertEquals(4, viaD.members().size());
    }

    @Test
    @DisplayName("组关系全局共享：不同公告独立核算但都按组执行每访客上限")
    void groupRelationGlobalAcrossCampaigns() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 1));
        service.createCampaign(new CreateCampaignRequest("req-c2", "g-c2", 10, 1));
        merge("req-m1", "ga", "gb", 0);

        service.apply(new ApplyExposureRequest("req-a1", "g-c1", "ga"));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a2", "g-c1", "gb")));

        // 另一公告独立核算，但同样按组共享上限
        service.apply(new ApplyExposureRequest("req-a3", "g-c2", "ga"));
        assert429(() -> service.apply(new ApplyExposureRequest("req-a4", "g-c2", "gb")));

        assertEquals(1, service.queryQuota("g-c1", null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("g-c2", null, DAY).usedTotal());
        assertEquals(1, service.queryGroupQuota("gb", "g-c1", DAY).usedGroup());
        assertEquals(1, service.queryGroupQuota("gb", "g-c2", DAY).usedGroup());
    }

    @Test
    @DisplayName("组查询：未归并访客 404，公告不存在 404")
    void groupQueryNotFound() {
        service.createCampaign(new CreateCampaignRequest("req-c1", "g-c1", 10, 2));
        assert404(() -> service.queryGroupQuota("ghost", "g-c1", DAY));
        merge("req-m1", "ga", "gb", 0);
        assert404(() -> service.queryGroupQuota("ga", "nope", DAY));
    }

    @Test
    @DisplayName("HTTP 语义：归并 200、同编号 400、缺操作者 400、组额度查询 200")
    void httpSemantics() throws Exception {
        mockMvc.perform(post("/api/exposure/identity/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hm1\",\"visitorIdA\":\"ha\",\"visitorIdB\":\"hb\","
                                + "\"operator\":\"op\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.members.length()").value(2));

        mockMvc.perform(post("/api/exposure/identity/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hm2\",\"visitorIdA\":\"ha\",\"visitorIdB\":\"ha\","
                                + "\"operator\":\"op\",\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/exposure/identity/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"hm3\",\"visitorIdA\":\"hx\",\"visitorIdB\":\"hy\","
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());

        service.createCampaign(new CreateCampaignRequest("req-c1", "g-ch", 10, 2));
        mockMvc.perform(get("/api/exposure/identity/groups/ha/quota")
                        .param("campaignId", "g-ch")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedGroup").value(0))
                .andExpect(jsonPath("$.members[0]").value("ha"));
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
