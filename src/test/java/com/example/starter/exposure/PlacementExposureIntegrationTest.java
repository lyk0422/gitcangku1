package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多展示位共享频控 H2（MODE=MySQL）集成测试：
 * 三层额度、跨展示位共享访客上限、DEFAULT 兼容、到期释放三层、配置版本与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacementExposureIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-09-23T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 23);

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
        jdbc.update("DELETE FROM quota_placement_ledger");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM placement");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private CreateCampaignRequest campaignReq(String requestId, String campaignId, int total, int perVisitor) {
        return new CreateCampaignRequest(requestId, campaignId, total, perVisitor);
    }

    @Test
    @DisplayName("创建公告：configVersion=1 且自动创建日额度等于公告总额度的 DEFAULT 展示位")
    void createCampaign_initializesVersionAndDefaultPlacement() {
        var campaign = service.createCampaign(campaignReq("req-c1", "c1", 100, 5));

        assertEquals(1, campaign.configVersion());

        List<PlacementResponse> placements = service.listPlacements("c1");
        assertEquals(1, placements.size());
        PlacementResponse def = placements.get(0);
        assertEquals("DEFAULT", def.placementCode());
        assertEquals(100, def.dailyCap());
        assertEquals(1, def.configVersion());

        // 不允许显式再创建 DEFAULT
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p0", "DEFAULT", 10, 1)));
    }

    @Test
    @DisplayName("新增展示位：版本严格递增；重复 code、超额、超过 20 个、过期版本分别报错")
    void createPlacement_versionAndValidationRules() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));

        PlacementResponse p1 = service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 30, 1));
        assertEquals(30, p1.dailyCap());
        assertEquals(2, p1.configVersion());

        PlacementResponse p1Again = service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 30, 1));
        assertEquals(p1.configVersion(), p1Again.configVersion(), "同键同参重放原结果");

        // 异参 409：同 requestId 改 code
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P2", 30, 2)));

        // 重复 code 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-pdup", "P1", 20, 2)));

        // 版本过期 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-pstale", "P2", 20, 1)));

        // 日额度超过公告总额度 400
        assert400(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-pbig", "P2", 101, 2)));

        // 公告不存在 404
        assert404(() -> service.createPlacement("nope",
                new CreatePlacementRequest("req-pn", "P2", 10, 1)));

        // 失败不占键：上面 req-pbig 因 400 回滚未占键，修正参数后同键可成功
        PlacementResponse p2 = service.createPlacement("c1",
                new CreatePlacementRequest("req-pbig", "P2", 50, 2));
        assertEquals(3, p2.configVersion());

        // 连续建满 20 个（已有 DEFAULT、P1、P2，再建 P3..P19 共 17 个）
        for (int i = 3; i <= 19; i++) {
            PlacementResponse created = service.createPlacement("c1",
                    new CreatePlacementRequest("req-p" + i, "P" + i, 1, i));
            assertEquals(i + 1, created.configVersion());
        }
        assertEquals(20, service.listPlacements("c1").size());
        // 第 21 个 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p20", "P20", 1, 20)));
    }

    @Test
    @DisplayName("旧申请接口等价于 DEFAULT：预占固定记录 DEFAULT 并占用 DEFAULT 展示位账")
    void legacyApply_equalsDefaultPlacement() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        assertEquals("DEFAULT", r.placementCode());

        QuotaResponse quota = service.queryQuota("c1", "v1", "DEFAULT", DAY);
        assertEquals("DEFAULT", quota.placementCode());
        assertEquals(100, quota.placementDailyCap());
        assertEquals(1, quota.usedPlacement());
        assertEquals(99, quota.remainingPlacement());
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());

        // 按展示位申请不存在的展示位 404
        assert404(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "GHOST", "v1")));
    }

    @Test
    @DisplayName("按展示位申请：同一事务同时占用三层额度并返回固定展示位")
    void applyPlacement_occupiesThreeLayers() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));
        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assertEquals("P1", r.placementCode());
        assertEquals(DAY, r.utcDate());
        assertEquals("RESERVED", r.status().name());

        QuotaResponse q = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(10, q.placementDailyCap());
        assertEquals(1, q.usedPlacement());
        assertEquals(9, q.remainingPlacement());
        assertEquals(1, q.usedTotal());
        assertEquals(1, q.usedVisitor());

        // 预占明细含展示位
        assertEquals(1, q.reservations().size());
        QuotaResponse.ReservationDetail detail = q.reservations().get(0);
        assertEquals("P1", detail.placementCode());
        assertEquals(r.reservationId(), detail.reservationId());
        assertEquals("RESERVED", detail.status());
    }

    @Test
    @DisplayName("访客每日上限跨全部展示位共享：跨 DEFAULT 与 P1 累计，超出后任一位均 429")
    void visitorCap_sharedAcrossPlacements() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 2));
        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 100, 1));

        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v1"));

        // 访客 v1 已在两个展示位各占 1 次，达到共享上限 2：再申请任一展示位均 429
        assert429(() -> service.apply(new ApplyExposureRequest("req-a3", "c1", "v1")));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a4", "c1", "P1", "v1")));

        // 三层账目中只有成功的两次
        QuotaResponse qDefault = service.queryQuota("c1", "v1", "DEFAULT", DAY);
        QuotaResponse qP1 = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(1, qDefault.usedPlacement());
        assertEquals(1, qP1.usedPlacement());
        assertEquals(2, qP1.usedTotal());
        assertEquals(2, qP1.usedVisitor());
        assertEquals(0, qP1.remainingVisitor());

        // 其他访客不受影响
        ReservationResponse other = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a5", "c1", "P1", "v2"));
        assertEquals("RESERVED", other.status().name());
    }

    @Test
    @DisplayName("三层额度各自独立判满：公告总额、访客上限、展示位额度已满均 429 且三层均不增加")
    void eachLayerFull_returns429WithoutAnyIncrement() {
        // 展示位额度层最小：P1 容量 1
        service.createCampaign(campaignReq("req-c1", "c1", 100, 100));
        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 1, 1));
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));

        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v2")));
        QuotaResponse qAfterPlacementFull = service.queryQuota("c1", "v2", "P1", DAY);
        assertEquals(1, qAfterPlacementFull.usedTotal(), "展示位满不影响总账");
        assertEquals(0, qAfterPlacementFull.usedVisitor());
        assertEquals(1, qAfterPlacementFull.usedPlacement());

        // 公告总额度层最小：总额 1（各展示位额度受其约束也只能为 1），DEFAULT 占满后其他展示位无法再申请
        service.createCampaign(campaignReq("req-c2", "c2", 1, 100));
        service.createPlacement("c2", new CreatePlacementRequest("req-p2", "P1", 1, 1));
        service.apply(new ApplyExposureRequest("req-b1", "c2", "v1"));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-b2", "c2", "P1", "v9")));
        assertEquals(0, service.queryQuota("c2", "v9", "P1", DAY).usedPlacement());

        // 失败不占键：P1 释放出容量后（到期），同键同参可成功
        mutableClock().advanceMillis(60_000L);
        ReservationResponse retried = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v2"));
        assertEquals("RESERVED", retried.status().name());
    }

    @Test
    @DisplayName("确认持续占用三层；取消与到期恰好释放三层且不重复释放")
    void confirmCancelExpire_threeLayersKeepOrReleaseOnce() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));
        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse confirmOne = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        service.confirm(confirmOne.reservationId(), new ReservationActionRequest("req-k1"));

        ReservationResponse cancelOne = service.apply(new ApplyExposureRequest("req-a2", "c1", "v2"));
        service.cancel(cancelOne.reservationId(), new ReservationActionRequest("req-x1"));

        ReservationResponse expireOne = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P1", "v3"));

        // 重复取消不重复释放
        service.cancel(cancelOne.reservationId(), new ReservationActionRequest("req-x2"));

        mutableClock().advanceMillis(60_000L);
        // 到期结算发生在任意业务中；对已取消单再取消返回原状态、不释放
        service.cancel(cancelOne.reservationId(), new ReservationActionRequest("req-x3"));
        assert409(() -> service.confirm(expireOne.reservationId(),
                new ReservationActionRequest("req-k2")));
        assertEquals("EXPIRED", service.getReservation(expireOne.reservationId()).status().name());

        // P1：v1 确认仍占 1，v3 到期释放，最终展示位账为 1
        QuotaResponse qP1v1 = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(1, qP1v1.usedPlacement());
        int p1Ledger = jdbc.queryForObject(
                "SELECT used_placement FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'c1' AND placement_code = 'P1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int defaultLedger = jdbc.queryForObject(
                "SELECT used_placement FROM quota_placement_ledger "
                        + "WHERE campaign_id = 'c1' AND placement_code = 'DEFAULT' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        int totalLedger = jdbc.queryForObject(
                "SELECT used_total FROM quota_total_ledger WHERE campaign_id = 'c1' AND utc_date = ?",
                Integer.class, java.sql.Date.valueOf(DAY));
        assertEquals(1, p1Ledger, "确认单持续占用、到期单释放");
        assertEquals(0, defaultLedger, "取消单恰好释放一次");
        assertEquals(1, totalLedger, "总账仅剩确认单");
    }

    @Test
    @DisplayName("额度查询兼容旧响应：不传 placementCode 时展示位层字段为 null，旧字段不变")
    void quotaQuery_backwardCompatibleWithoutPlacementParam() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));
        service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        QuotaResponse oldShape = service.queryQuota("c1", "v1", DAY);
        assertNull(oldShape.placementCode());
        assertNull(oldShape.placementDailyCap());
        assertNull(oldShape.usedPlacement());
        assertNull(oldShape.remainingPlacement());
        assertEquals(100, oldShape.dailyTotalCap());
        assertEquals(1, oldShape.usedTotal());
        assertEquals(99, oldShape.remainingTotal());
        assertEquals(5, oldShape.perVisitorDailyCap());
        assertEquals(1, oldShape.usedVisitor());
        // 不传展示位时访客明细仍跨全部展示位返回
        assertEquals(1, oldShape.reservations().size());
        assertEquals("DEFAULT", oldShape.reservations().get(0).placementCode());
    }

    @Test
    @DisplayName("已创建预占不受后来展示位增加影响")
    void existingReservation_unaffectedByLaterPlacement() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 5));
        ReservationResponse before = service.apply(new ApplyExposureRequest("req-a1", "c1", "v1"));

        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse reloaded = service.getReservation(before.reservationId());
        assertEquals("DEFAULT", reloaded.placementCode());
        assertEquals("RESERVED", reloaded.status().name());
        assertEquals(before.expiresAtUtc(), reloaded.expiresAtUtc());
    }

    @Test
    @DisplayName("按展示位申请幂等：同键同参重放原预占，异参 409，429 失败不占键")
    void applyPlacement_idempotent() {
        service.createCampaign(campaignReq("req-c1", "c1", 100, 100));
        service.createPlacement("c1", new CreatePlacementRequest("req-p1", "P1", 1, 1));

        ReservationResponse first = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P1", "v1"));
        ReservationResponse replay = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P1", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());

        // 同键异参（不同展示位/访客）409
        assert409(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P1", "v2")));
        // 同键用于其他操作 409
        assert409(() -> service.confirm(first.reservationId(),
                new ReservationActionRequest("key-1")));

        // 展示位满导致 429，不占键；取消释放后同键可成功
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P1", "v2")));
        service.cancel(first.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P1", "v2"));
        assertNotNull(retried.reservationId());
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());
    }

    @Test
    @DisplayName("HTTP 语义：新增展示位 201、版本冲突 409、展示位满 429、额度查询展示位层")
    void httpSemantics_placementEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":100,"
                                + "\"perVisitorDailyCap\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configVersion").value(1));

        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"placementCode\":\"P1\",\"dailyCap\":2,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // 版本过期
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"placementCode\":\"P2\",\"dailyCap\":2,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"));

        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u3\"}"))
                .andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("placementCode", "P1")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedPlacement").value(2))
                .andExpect(jsonPath("$.placementDailyCap").value(2))
                .andExpect(jsonPath("$.reservations[0].placementCode").value("P1"));

        // 旧额度查询仍可用且展示位层缺省
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(2))
                .andExpect(jsonPath("$.placementCode").doesNotExist());
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert400(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(400, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
