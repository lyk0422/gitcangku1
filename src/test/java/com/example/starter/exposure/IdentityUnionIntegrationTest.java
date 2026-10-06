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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份归并 H2（MODE=MySQL）集成测试：归并主流程、版本冲突、组成员上限、
 * 超上限归并、跨公告独立核算、过期释放与幂等语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityUnionIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-10-06T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 10, 6);

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
        jdbc.update("DELETE FROM identity_group_member");
        jdbc.update("DELETE FROM identity_group");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private ReservationResponse apply(String requestId, String campaignId, String visitorId) {
        return service.apply(new ApplyExposureRequest(requestId, campaignId, visitorId));
    }

    private IdentityGroupResponse merge(String requestId, String a, String b, int expectedVersion) {
        return service.mergeIdentities(new MergeIdentityRequest(requestId, "tester", a, b, expectedVersion));
    }

    @Test
    @DisplayName("归并后两编号共享上限：各确认1次后新申请均429，历史归属不变")
    void mergeSharesCapAndKeepsHistory() {
        createCampaign("req-c1", "c1", 100, 2);
        ReservationResponse ra = apply("req-a1", "c1", "A");
        service.confirm(ra.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse rb = apply("req-b1", "c1", "B");
        service.confirm(rb.reservationId(), new ReservationActionRequest("req-k2"));

        IdentityGroupResponse group = merge("req-m1", "A", "B", 0);
        assertTrue(group.changed());
        assertEquals(1, group.version());
        assertEquals(List.of("A", "B"), group.memberVisitorIds());
        assertNotNull(group.groupId());

        // 组合计 2 达到上限：两编号新申请都 429
        assert429(() -> apply("req-a2", "c1", "A"));
        assert429(() -> apply("req-b2", "c1", "B"));

        // 历史确认仍归属原编号，申请日不变
        QuotaResponse qa = service.queryQuota("c1", "A", DAY);
        assertEquals(1, qa.usedVisitor());
        QuotaResponse qb = service.queryQuota("c1", "B", DAY);
        assertEquals(1, qb.usedVisitor());
        assertEquals(LocalDate.of(2026, 10, 6),
                service.getReservation(ra.reservationId()).utcDate());
        assertEquals("A", service.getReservation(ra.reservationId()).visitorId());
        assertEquals("B", service.getReservation(rb.reservationId()).visitorId());

        // 组视图：成员、合计与原编号分账
        GroupQuotaResponse gq = service.queryGroupQuota("c1", "A", DAY);
        assertEquals(group.groupId(), gq.groupId());
        assertEquals(2, gq.perVisitorDailyCap());
        assertEquals(2, gq.usedGroup());
        assertEquals(0, gq.remainingGroup());
        assertEquals(List.of(new GroupQuotaResponse.MemberUsage("A", 1),
                new GroupQuotaResponse.MemberUsage("B", 1)), gq.members());

        assertEquals(group.groupId(), service.getIdentityGroup("B").groupId());
    }

    @Test
    @DisplayName("归并幂等：同键同参重放首次完整响应；异参409；失败不占键")
    void mergeIdempotency_replayConflictAndFailureNotOccupied() {
        IdentityGroupResponse first = merge("req-m1", "A", "B", 0);

        IdentityGroupResponse replay = merge("req-m1", "A", "B", 0);
        assertEquals(first, replay);
        assertEquals(2, service.getIdentityGroup("A").memberVisitorIds().size());

        // 同键异参（不同编号）→ 409
        assert409(() -> merge("req-m1", "A", "C", 0));
        // 同键异参（不同操作者）→ 409
        assert409(() -> service.mergeIdentities(
                new MergeIdentityRequest("req-m1", "other", "A", "B", 0)));

        // 版本不符的失败归并不占键：修正版本后同键可成功
        assert409(() -> merge("req-m2", "A", "C", 5));
        IdentityGroupResponse retried = merge("req-m2", "A", "C", 1);
        assertTrue(retried.changed());
        assertEquals(List.of("A", "B", "C"), retried.memberVisitorIds());
        assertEquals(2, retried.version());
    }

    @Test
    @DisplayName("版本冲突409；跨组归并B侧并入A侧；同组重复归并不新增成员、版本不变")
    void mergeVersionConflictAndCrossGroupMerge() {
        IdentityGroupResponse g1 = merge("req-m1", "A", "B", 0);
        IdentityGroupResponse g2 = merge("req-m2", "C", "D", 0);

        // 版本不符 → 409
        assert409(() -> merge("req-m3", "A", "C", 0));

        // 跨组归并：B 侧组（C、D）并入 A 侧组
        IdentityGroupResponse merged = merge("req-m4", "A", "C", 1);
        assertTrue(merged.changed());
        assertEquals(g1.groupId(), merged.groupId());
        assertEquals(2, merged.version());
        assertEquals(List.of("A", "B", "C", "D"), merged.memberVisitorIds());
        assertEquals(g1.groupId(), service.getIdentityGroup("D").groupId());

        // 已同组重复归并：不新增成员、版本不变
        IdentityGroupResponse noop = merge("req-m5", "B", "D", 2);
        assertFalse(noop.changed());
        assertEquals(2, noop.version());
        assertEquals(4, noop.memberVisitorIds().size());

        Integer groupCount = jdbc.queryForObject("SELECT COUNT(*) FROM identity_group", Integer.class);
        assertEquals(1, groupCount);
    }

    @Test
    @DisplayName("组最多10个编号：第11个加入返回409")
    void groupMemberLimit() {
        IdentityGroupResponse group = merge("req-m0", "v0", "v1", 0);
        int version = group.version();
        for (int i = 2; i < 10; i++) {
            group = merge("req-m" + i, "v0", "v" + i, version);
            version = group.version();
        }
        assertEquals(10, group.memberVisitorIds().size());
        final int currentVersion = version;
        assert409(() -> merge("req-m10", "v0", "v10", currentVersion));
        assertEquals(10, service.getIdentityGroup("v0").memberVisitorIds().size());
    }

    @Test
    @DisplayName("合计超上限归并仍生效：组禁止新增但不取消既有预占，释放后可再申请")
    void mergeOverCapStillEffective_groupBlockedUntilRelease() {
        createCampaign("req-c1", "c1", 100, 2);
        ReservationResponse a1 = apply("req-a1", "c1", "A");
        ReservationResponse a2 = apply("req-a2", "c1", "A");
        ReservationResponse b1 = apply("req-b1", "c1", "B");

        // 合计 3 超过上限 2：归并仍生效
        IdentityGroupResponse group = merge("req-m1", "A", "B", 0);
        assertTrue(group.changed());

        // 既有预占不取消、额度不变负
        assertEquals("RESERVED", service.getReservation(a1.reservationId()).status().name());
        assertEquals(3, service.queryQuota("c1", null, DAY).usedTotal());
        GroupQuotaResponse gq = service.queryGroupQuota("c1", "A", DAY);
        assertEquals(3, gq.usedGroup());
        assertEquals(0, gq.remainingGroup());

        // 组禁止新增
        assert429(() -> apply("req-a3", "c1", "A"));
        assert429(() -> apply("req-b2", "c1", "B"));

        // 取消只释放原预占一次：合计 3 -> 2 仍满，-> 1 后可再申请
        service.cancel(a1.reservationId(), new ReservationActionRequest("req-x1"));
        assertEquals(2, service.queryGroupQuota("c1", "A", DAY).usedGroup());
        assert429(() -> apply("req-a4", "c1", "A"));
        service.cancel(b1.reservationId(), new ReservationActionRequest("req-x2"));
        assertEquals(1, service.queryGroupQuota("c1", "A", DAY).usedGroup());

        ReservationResponse again = apply("req-b3", "c1", "B");
        assertEquals("RESERVED", again.status().name());
        assertEquals(2, service.queryGroupQuota("c1", "A", DAY).usedGroup());
        assertTrue(service.queryQuota("c1", null, DAY).usedTotal() >= 0);
    }

    @Test
    @DisplayName("组关系全局共享，不同公告独立核算")
    void groupSharedAcrossCampaigns_independentAccounting() {
        createCampaign("req-c1", "c1", 10, 1);
        createCampaign("req-c2", "c2", 10, 1);
        IdentityGroupResponse group = merge("req-m1", "A", "B", 0);

        apply("req-a1", "c1", "A");
        // 同公告：B 受组合计限制
        assert429(() -> apply("req-b1", "c1", "B"));
        // 不同公告独立核算：B 在 c2 可申请
        ReservationResponse rb2 = apply("req-b2", "c2", "B");
        assertEquals("RESERVED", rb2.status().name());

        GroupQuotaResponse gq1 = service.queryGroupQuota("c1", "B", DAY);
        assertEquals(1, gq1.usedGroup());
        assertEquals(0, gq1.remainingGroup());
        GroupQuotaResponse gq2 = service.queryGroupQuota("c2", "B", DAY);
        assertEquals(1, gq2.usedGroup());
        assertEquals(group.groupId(), gq2.groupId());
    }

    @Test
    @DisplayName("归并后过期只释放原预占一次，合计回落后可再申请")
    void expiryAfterMerge_releasesOnce() {
        createCampaign("req-c1", "c1", 100, 2);
        ReservationResponse a1 = apply("req-a1", "c1", "A");
        ReservationResponse a2 = apply("req-a2", "c1", "A");
        merge("req-m1", "A", "B", 0);

        mutableClock().advanceMillis(60_000L);

        // B 的申请先结算过期：A 的两单恰好释放一次，B 申请成功
        ReservationResponse b1 = apply("req-b1", "c1", "B");
        assertEquals("RESERVED", b1.status().name());

        GroupQuotaResponse gq = service.queryGroupQuota("c1", "A", DAY);
        assertEquals(1, gq.usedGroup());
        assertEquals(List.of(new GroupQuotaResponse.MemberUsage("A", 0),
                new GroupQuotaResponse.MemberUsage("B", 1)), gq.members());
        assertEquals("EXPIRED", service.getReservation(a1.reservationId()).status().name());
        assertEquals("EXPIRED", service.getReservation(a2.reservationId()).status().name());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("HTTP 语义：归并 200 与重放、组查询、未入组 404、缺参 400")
    void httpSemantics_mergeGroupAndQuota() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":2}"))
                .andExpect(status().isCreated());

        String mergeBody = "{\"requestId\":\"h2\",\"operator\":\"ops\",\"visitorIdA\":\"A\","
                + "\"visitorIdB\":\"B\",\"expectedVersion\":0}";
        String groupId = com.jayway.jsonpath.JsonPath.read(mockMvc.perform(
                        post("/api/exposure/identity/merges")
                                .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.memberVisitorIds[0]").value("A"))
                .andReturn().getResponse().getContentAsString(), "$.groupId");

        // 同键同参重放：同一组
        mockMvc.perform(post("/api/exposure/identity/merges")
                        .contentType(MediaType.APPLICATION_JSON).content(mergeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId));

        mockMvc.perform(get("/api/exposure/identity/groups/A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId))
                .andExpect(jsonPath("$.memberVisitorIds[1]").value("B"));

        mockMvc.perform(get("/api/exposure/identity/groups/stranger"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"campaignId\":\"ch\",\"visitorId\":\"A\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/exposure/campaigns/ch/group-quota")
                        .param("visitorId", "B").param("utcDate", "2026-10-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId))
                .andExpect(jsonPath("$.usedGroup").value(1))
                .andExpect(jsonPath("$.members[0].visitorId").value("A"))
                .andExpect(jsonPath("$.members[0].usedVisitor").value(1))
                .andExpect(jsonPath("$.members[1].visitorId").value("B"))
                .andExpect(jsonPath("$.members[1].usedVisitor").value(0));

        // 缺少操作者 → 400
        mockMvc.perform(post("/api/exposure/identity/merges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"visitorIdA\":\"X\",\"visitorIdB\":\"Y\","
                                + "\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }
}