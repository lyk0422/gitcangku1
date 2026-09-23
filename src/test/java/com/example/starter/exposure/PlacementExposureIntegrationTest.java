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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多展示位共享频控 H2（MODE=MySQL）集成测试：
 * DEFAULT 兼容、展示位配置与 configVersion、三层额度、跨位共享访客上限、
 * 到期三层释放、预占明细、幂等与 HTTP 语义。
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
        jdbc.update("DELETE FROM campaign_placement");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(
                "req-c-" + campaignId, campaignId, total, perVisitor));
    }

    @Test
    @DisplayName("创建公告同事务创建 DEFAULT 展示位：日额度等于公告总额度，configVersion=1")
    void createCampaign_createsDefaultPlacement() {
        createCampaign("c1", 100, 5);

        List<PlacementResponse> placements = service.listPlacements("c1");
        assertEquals(1, placements.size());
        PlacementResponse def = placements.get(0);
        assertEquals("DEFAULT", def.placementCode());
        assertEquals(100, def.dailyCap());
        assertEquals(1, def.configVersion());

        QuotaResponse quota = service.queryQuota("c1", null, "DEFAULT", DAY);
        assertEquals(1, quota.configVersion());
        assertEquals(100, quota.placementDailyCap());
        assertEquals(0, quota.usedPlacement());
        assertEquals(100, quota.remainingPlacement());
        assertNull(quota.usedVisitor());
    }

    @Test
    @DisplayName("新增展示位：版本逐个递增、列表按版本排序、不可重复创建 DEFAULT")
    void createPlacement_incrementsVersionAndListsOrdered() {
        createCampaign("c1", 100, 5);

        PlacementResponse p1 = service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 10, 1));
        assertEquals("P1", p1.placementCode());
        assertEquals(10, p1.dailyCap());
        assertEquals(2, p1.configVersion());

        PlacementResponse p2 = service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 20, 2));
        assertEquals(3, p2.configVersion());

        List<PlacementResponse> all = service.listPlacements("c1");
        assertEquals(List.of("DEFAULT", "P1", "P2"),
                all.stream().map(PlacementResponse::placementCode).toList());
        assertEquals(List.of(1, 2, 3),
                all.stream().map(PlacementResponse::configVersion).toList());

        // DEFAULT 由公告创建时自动建立，不能重复创建
        assert400(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-pd", "DEFAULT", 100, 3)));
    }

    @Test
    @DisplayName("展示位配置失败分支：版本不符 409、重复 code 409、超总额 400、未知公告 404")
    void createPlacement_failureBranches() {
        createCampaign("c1", 10, 5);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 10, 1));

        // 当前版本已为 2，仍传 1 → 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 5, 1)));
        // 日额度超过公告总额度 → 400
        assert400(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p3", "P3", 11, 2)));
        // 正确版本创建 P2
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2b", "P2", 5, 2));
        // 重复 code → 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p2c", "P2", 5, 3)));
        // 未知公告 → 404
        assert404(() -> service.createPlacement("nope",
                new CreatePlacementRequest("req-p4", "P4", 5, 1)));
        // 列表查询未知公告 → 404
        assert404(() -> service.listPlacements("nope"));
    }

    @Test
    @DisplayName("展示位数量上限 20：第 21 个唯一 code 返回 409")
    void createPlacement_max20Enforced() {
        createCampaign("c1", 100_000, 5);
        for (int i = 1; i <= 19; i++) {
            String code = String.format("P%02d", i);
            PlacementResponse created = service.createPlacement("c1",
                    new CreatePlacementRequest("req-p-" + i, code, 1, i));
            assertEquals(i + 1, created.configVersion());
        }
        assertEquals(20, service.listPlacements("c1").size());

        ApiException ex = assertThrows(ApiException.class, () -> service.createPlacement("c1",
                new CreatePlacementRequest("req-p-21", "P20", 1, 20)));
        assertEquals(409, ex.getStatus().value());
        assertEquals(20, service.listPlacements("c1").size());
    }

    @Test
    @DisplayName("展示位创建幂等：同键同参重放原结果；异参 409；失败不占键")
    void createPlacement_idempotent() {
        createCampaign("c1", 100, 5);

        PlacementResponse first = service.createPlacement("c1",
                new CreatePlacementRequest("key-p1", "P1", 10, 1));
        PlacementResponse replay = service.createPlacement("c1",
                new CreatePlacementRequest("key-p1", "P1", 10, 1));
        assertEquals(first.configVersion(), replay.configVersion());
        assertEquals(2, service.listPlacements("c1").size());

        // 同键异参（不同 code）→ 409
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("key-p1", "P2", 10, 1)));
        // 同键用于其他操作 → 409
        assert409(() -> service.apply(new ApplyExposureRequest("key-p1", "c1", "v1")));

        // 版本不符的失败不占键：版本追平后同键同参可成功
        assert409(() -> service.createPlacement("c1",
                new CreatePlacementRequest("key-pf", "PF", 10, 1)));
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 10, 2));
        PlacementResponse retried = service.createPlacement("c1",
                new CreatePlacementRequest("key-pf", "PF", 10, 3));
        assertEquals(4, retried.configVersion());
    }

    @Test
    @DisplayName("按展示位申请成功时三层账目同时 +1，预占固定申请日与展示位")
    void applyPlacement_occupiesThreeLayers() {
        createCampaign("c1", 100, 5);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assertEquals("P1", r.placementCode());
        assertEquals(DAY, r.utcDate());
        assertEquals("RESERVED", r.status().name());

        QuotaResponse all = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(1, all.usedTotal());
        assertEquals(1, all.usedVisitor());
        assertEquals(1, all.usedPlacement());
        assertEquals(99, all.remainingTotal());
        assertEquals(4, all.remainingVisitor());
        assertEquals(9, all.remainingPlacement());
        assertEquals(1, all.reservations().size());
        assertEquals("P1", all.reservations().get(0).placementCode());
        assertEquals(r.reservationId(), all.reservations().get(0).reservationId());
    }

    @Test
    @DisplayName("访客上限跨全部展示位共享：第三位申请 429，三层账目均不增加")
    void visitorCap_sharedAcrossPlacements() {
        createCampaign("c1", 100, 2);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 100, 1));
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 100, 2));

        service.applyPlacement(new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        service.applyPlacement(new ApplyPlacementExposureRequest("req-a2", "c1", "P2", "v1"));

        // 访客共享上限 2 已满：再申请任何展示位均 429
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P1", "v1")));

        // 三层账目保持 2/2/各 1，失败申请未增加任何一层
        QuotaResponse p1 = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(2, p1.usedTotal());
        assertEquals(2, p1.usedVisitor());
        assertEquals(1, p1.usedPlacement());
        QuotaResponse p2 = service.queryQuota("c1", "v1", "P2", DAY);
        assertEquals(1, p2.usedPlacement());

        // 其他访客仍可在展示位容量内申请
        ReservationResponse other = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a4", "c1", "P1", "v2"));
        assertEquals("RESERVED", other.status().name());
    }

    @Test
    @DisplayName("展示位额度独立：某展示位满 429 不影响其他展示位，失败三层不增加")
    void placementCap_isolatedPerPlacement() {
        createCampaign("c1", 100, 100);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 1, 1));
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 1, 2));

        service.applyPlacement(new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        assert429(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v2")));
        // P1 已满未占用：总额仍为 1，P1 用量仍为 1
        assertEquals(1, service.queryQuota("c1", null, null, DAY).usedTotal());
        assertEquals(1, service.queryQuota("c1", null, "P1", DAY).usedPlacement());

        // P2 独立容量不受影响
        ReservationResponse onP2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P2", "v2"));
        assertEquals("P2", onP2.placementCode());
        assertEquals(1, service.queryQuota("c1", null, "P2", DAY).usedPlacement());

        // 公告总额度满同样 429 且三层不增加（总额 2/100 远未满，此处用小公告单测总层）
        createCampaign("c2", 1, 100);
        service.apply(new ApplyExposureRequest("req-b1", "c2", "v9"));
        assert429(() -> service.apply(new ApplyExposureRequest("req-b2", "c2", "v8")));
        assertEquals(1, service.queryQuota("c2", null, "DEFAULT", DAY).usedTotal());
        assertEquals(0, service.queryQuota("c2", "v8", "DEFAULT", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c2", "v8", "DEFAULT", DAY).usedPlacement(),
                "展示位 DEFAULT 与总额同步已满，失败申请不增加展示位账");
    }

    @Test
    @DisplayName("取消与到期同事务释放三层额度；确认持续占用；已建预占不受新增展示位影响")
    void cancelAndExpiry_releaseThreeLayers_confirmKeeps() {
        createCampaign("c1", 100, 5);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse r1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        // 取消释放三层
        assertEquals("CANCELLED", service.cancel(r1.reservationId(),
                new ReservationActionRequest("req-x1")).status().name());
        QuotaResponse afterCancel = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(0, afterCancel.usedTotal());
        assertEquals(0, afterCancel.usedVisitor());
        assertEquals(0, afterCancel.usedPlacement());
        assertTrue(afterCancel.reservations().isEmpty());

        // 再申请一笔并确认：确认后三层持续占用
        ReservationResponse r2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P1", "v1"));
        assertEquals("CONFIRMED", service.confirm(r2.reservationId(),
                new ReservationActionRequest("req-k1")).status().name());

        // 已创建的预占不受后来展示位增加影响
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 10, 2));
        ReservationResponse kept = service.getReservation(r2.reservationId());
        assertEquals("CONFIRMED", kept.status().name());
        assertEquals("P1", kept.placementCode());
        QuotaResponse afterConfig = service.queryQuota("c1", "v1", "P1", DAY);
        assertEquals(1, afterConfig.usedTotal());
        assertEquals(1, afterConfig.usedVisitor());
        assertEquals(1, afterConfig.usedPlacement());
        assertEquals(3, afterConfig.configVersion());

        // 第三笔走到到期：推进到恰好到期时刻，结算后三层释放
        ReservationResponse r3 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P1", "v2"));
        mutableClock().advanceMillis(60_000L);
        assert409(() -> service.confirm(r3.reservationId(),
                new ReservationActionRequest("req-e1")));
        assertEquals("EXPIRED", service.getReservation(r3.reservationId()).status().name());
        QuotaResponse afterExpiry = service.queryQuota("c1", null, "P1", DAY);
        // r2 确认仍占 1；r3 过期释放
        assertEquals(1, afterExpiry.usedTotal());
        assertEquals(1, afterExpiry.usedPlacement());
        assertEquals(0, service.queryQuota("c1", "v2", "P1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("旧申请接口等价于 DEFAULT；按展示位申请的幂等与异参 409")
    void legacyApplyEqualsDefault_andApplyIdempotency() {
        createCampaign("c1", 10, 2);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 10, 1));

        ReservationResponse legacy = service.apply(
                new ApplyExposureRequest("req-l1", "c1", "v1"));
        assertEquals("DEFAULT", legacy.placementCode());
        assertEquals(1, service.queryQuota("c1", "v1", "DEFAULT", DAY).usedPlacement());

        ReservationResponse placed = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-n1", "c1", "P1", "v1"));
        // 同键同参重放
        ReservationResponse replay = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-n1", "c1", "P1", "v1"));
        assertEquals(placed.reservationId(), replay.reservationId());
        // 同键异参（换展示位）→ 409
        assert409(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-n1", "c1", "DEFAULT", "v1")));
        // 不存在的展示位申请 → 404
        assert404(() -> service.applyPlacement(
                new ApplyPlacementExposureRequest("req-n2", "c1", "NOPE", "v1")));

        // 429 失败不占键：取消 DEFAULT 预占后同键同参可成功
        assert429(() -> service.apply(
                new ApplyExposureRequest("req-lf", "c1", "v1")));
        service.cancel(legacy.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse retried = service.apply(
                new ApplyExposureRequest("req-lf", "c1", "v1"));
        assertEquals("DEFAULT", retried.placementCode());
    }

    @Test
    @DisplayName("额度查询的预占明细按 公告/访客/展示位/UTC日 过滤；终态单不再出现")
    void quotaQuery_reservationDetailsFiltered() {
        createCampaign("c1", 100, 100);
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p1", "P1", 100, 1));
        service.createPlacement("c1",
                new CreatePlacementRequest("req-p2", "P2", 100, 2));

        ReservationResponse r1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a1", "c1", "P1", "v1"));
        ReservationResponse r2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a2", "c1", "P2", "v1"));
        ReservationResponse r3 = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a3", "c1", "P1", "v2"));
        service.cancel(r3.reservationId(), new ReservationActionRequest("req-x3"));

        // 公告维度：2 条占用（已取消的 r3 不出现）
        assertEquals(2, service.queryQuota("c1", null, null, DAY).reservations().size());
        // 访客维度：v1 的两条
        List<QuotaResponse.ReservationDetail> byVisitor =
                service.queryQuota("c1", "v1", null, DAY).reservations();
        assertEquals(2, byVisitor.size());
        assertTrue(byVisitor.stream().allMatch(d -> d.visitorId().equals("v1")));
        // 展示位维度：P1 仅 r1
        List<QuotaResponse.ReservationDetail> byPlacement =
                service.queryQuota("c1", null, "P1", DAY).reservations();
        assertEquals(List.of(r1.reservationId()),
                byPlacement.stream().map(QuotaResponse.ReservationDetail::reservationId).toList());
        // 访客 + 展示位交集
        assertEquals(1, service.queryQuota("c1", "v1", "P2", DAY).reservations().size());
        // 查询不存在的展示位 → 404
        assert404(() -> service.queryQuota("c1", null, "NOPE", DAY));
        // 其他 UTC 日无账目无明细
        QuotaResponse otherDay = service.queryQuota("c1", null, null, LocalDate.of(2026, 9, 24));
        assertEquals(0, otherDay.usedTotal());
        assertTrue(otherDay.reservations().isEmpty());
        assertFalse(otherDay.reservations().stream().findAny().isPresent());
    }

    @Test
    @DisplayName("HTTP 语义：展示位 201/列表 200/版本冲突 409/参数越界 400/按位申请与三层额度查询")
    void httpSemantics_forPlacementEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configVersion").value(1));

        mockMvc.perform(get("/api/exposure/campaigns/ch/placements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].placementCode").value("DEFAULT"))
                .andExpect(jsonPath("$[0].dailyCap").value(10))
                .andExpect(jsonPath("$[0].configVersion").value(1));

        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"placementCode\":\"P1\",\"dailyCap\":3,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // 过期版本再建 → 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"placementCode\":\"P2\",\"dailyCap\":3,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 日额度越界（0）→ 400
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"placementCode\":\"P2\",\"dailyCap\":0,"
                                + "\"expectedConfigVersion\":2}"))
                .andExpect(status().isBadRequest());

        // 按展示位申请 → 201
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"P1\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        // 三层额度查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("placementCode", "P1")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(1))
                .andExpect(jsonPath("$.usedVisitor").value(1))
                .andExpect(jsonPath("$.usedPlacement").value(1))
                .andExpect(jsonPath("$.placementCode").value("P1"))
                .andExpect(jsonPath("$.reservations[0].placementCode").value("P1"));

        // 旧接口仍可用且落到 DEFAULT
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"ch\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("DEFAULT"));
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
