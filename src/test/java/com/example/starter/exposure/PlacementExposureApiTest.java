package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.ApplyPlacementExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreatePlacementRequest;
import com.example.starter.exposure.web.PlacementResponse;
import com.example.starter.exposure.web.QuotaDetailResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.ApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多展示位共享频控 H2（MODE=MySQL）集成测试：
 * DEFAULT 兼容、展示位配置与版本、三层额度占用/释放、跨展示位访客共享上限、明细与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlacementExposureApiTest {

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

    @org.springframework.boot.test.context.TestConfiguration
    static class TestClockConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService service;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;
    @Autowired
    MockMvc mockMvc;

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

    private void createCampaign(String reqId, String id, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest(reqId, id, total, perVisitor));
    }

    private PlacementResponse addPlacement(String reqId, String campaign, String code, int cap, int version) {
        return service.createPlacement(campaign,
                new CreatePlacementRequest(reqId, code, cap, version));
    }

    @Test
    @DisplayName("创建公告自动生成 DEFAULT 展示位：configVersion=1，日额度等于公告总额")
    void createCampaign_createsDefaultPlacement() {
        createCampaign("req-c", "c1", 100, 5);

        List<PlacementResponse> placements = service.listPlacements("c1");
        assertEquals(1, placements.size());
        PlacementResponse def = placements.get(0);
        assertEquals("DEFAULT", def.placementCode());
        assertEquals(100, def.dailyCap());
        assertEquals(1, def.configVersion());
        assertEquals(1, service.listPlacements("c1").get(0).configVersion());
    }

    @Test
    @DisplayName("新增展示位成功：版本从 1 递增到 2，列表含 DEFAULT 与新展示位")
    void createPlacement_incrementsConfigVersion() {
        createCampaign("req-c", "c1", 100, 5);
        PlacementResponse p = addPlacement("req-p", "c1", "BANNER", 30, 1);

        assertEquals("BANNER", p.placementCode());
        assertEquals(30, p.dailyCap());
        assertEquals(2, p.configVersion());

        List<PlacementResponse> all = service.listPlacements("c1");
        assertEquals(2, all.size());
        assertEquals("DEFAULT", all.get(0).placementCode());
        assertEquals("BANNER", all.get(1).placementCode());
    }

    @Test
    @DisplayName("展示位日额度超过公告总额返回 400，不占幂等键、不产生展示位")
    void createPlacement_capExceedsTotal_returns400() {
        createCampaign("req-c", "c1", 10, 5);
        assertStatus(400, () -> addPlacement("req-p", "c1", "P", 11, 1));
        // 失败不占键：同键修正参数后可成功
        PlacementResponse p = addPlacement("req-p", "c1", "P", 10, 1);
        assertEquals(10, p.dailyCap());
        assertEquals(2, service.listPlacements("c1").size());
    }

    @Test
    @DisplayName("expectedConfigVersion 与当前版本不一致返回 409")
    void createPlacement_versionMismatch_returns409() {
        createCampaign("req-c", "c1", 100, 5);
        addPlacement("req-p1", "c1", "P1", 10, 1);
        // 当前版本已为 2，再以 expected=1 创建应 409
        assertStatus(409, () -> addPlacement("req-p2", "c1", "P2", 10, 1));
        // 以正确版本 2 创建成功
        PlacementResponse p2 = addPlacement("req-p2", "c1", "P2", 10, 2);
        assertEquals(3, p2.configVersion());
    }

    @Test
    @DisplayName("DEFAULT 展示位不可重复创建；重复 code 返回 409")
    void createPlacement_duplicateAndDefault_returns409() {
        createCampaign("req-c", "c1", 100, 5);
        assertStatus(409, () -> addPlacement("req-d", "c1", "DEFAULT", 10, 1));
        addPlacement("req-p1", "c1", "P1", 10, 1);
        assertStatus(409, () -> addPlacement("req-p2", "c1", "P1", 10, 2));
    }

    @Test
    @DisplayName("展示位最多 20 个（含 DEFAULT）：第 20 个额外展示位被拒 409")
    void createPlacement_maxTwentyLimit() {
        createCampaign("req-c", "c1", 100000, 100000);
        // DEFAULT 占 1 个，再创建 19 个成功，共 20
        for (int i = 1; i <= 19; i++) {
            addPlacement("req-p" + i, "c1", "P" + i, 1, i);
        }
        assertEquals(20, service.listPlacements("c1").size());
        // 第 21 个（第 20 个额外）被拒
        assertStatus(409, () -> addPlacement("req-p20", "c1", "P20", 1, 20));
        assertEquals(20, service.listPlacements("c1").size());
    }

    @Test
    @DisplayName("按展示位申请同时占用公告/访客/展示位三层额度")
    void applyPlacement_occupiesThreeLayers() {
        createCampaign("req-c", "c1", 100, 5);
        addPlacement("req-p", "c1", "BANNER", 30, 1);

        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("req-a", "c1", "BANNER", "v1"));
        assertEquals("BANNER", r.placementCode());
        assertEquals("RESERVED", r.status().name());
        assertEquals(DAY, r.utcDate());

        QuotaResponse q = service.queryQuota("c1", "v1", "BANNER", DAY);
        assertEquals(100, q.dailyTotalCap());
        assertEquals(1, q.usedTotal());
        assertEquals(99, q.remainingTotal());
        assertEquals(5, q.perVisitorDailyCap());
        assertEquals(1, q.usedVisitor());
        assertEquals(4, q.remainingVisitor());
        assertEquals(30, q.placementDailyCap());
        assertEquals(1, q.usedPlacement());
        assertEquals(29, q.remainingPlacement());
    }

    @Test
    @DisplayName("旧申请接口等价于 DEFAULT：占用 DEFAULT 展示位额度")
    void legacyApply_equalsDefaultPlacement() {
        createCampaign("req-c", "c1", 100, 5);
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a", "c1", "v1"));
        assertEquals("DEFAULT", r.placementCode());

        QuotaResponse q = service.queryQuota("c1", "v1", "DEFAULT", DAY);
        assertEquals(1, q.usedTotal());
        assertEquals(1, q.usedVisitor());
        assertEquals(1, q.usedPlacement());
        assertEquals(99, q.remainingPlacement());
    }

    @Test
    @DisplayName("访客上限跨全部展示位共享：两个展示位合计达到访客上限后第三位申请 429")
    void visitorCapSharedAcrossPlacements() {
        // 总额 100，访客共享上限 2；BANNER 额度 10，SIDEBAR 额度 10
        createCampaign("req-c", "c1", 100, 2);
        addPlacement("req-p1", "c1", "BANNER", 10, 1);
        addPlacement("req-p2", "c1", "SIDEBAR", 10, 2);

        service.applyPlacement(new ApplyPlacementExposureRequest("a1", "c1", "BANNER", "v1"));
        service.applyPlacement(new ApplyPlacementExposureRequest("a2", "c1", "SIDEBAR", "v1"));

        // 访客 v1 已在两个展示位各占 1，达到共享上限 2；再申请任一展示位被拒
        assertStatus(429, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("a3", "c1", "BANNER", "v1")));

        // 三层账目中 BANNER 与 SIDEBAR 各 1，访客共享 2，总额 2
        assertEquals(2, service.queryQuota("c1", "v1", "BANNER", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", "v1", "BANNER", DAY).usedPlacement());
        assertEquals(1, service.queryQuota("c1", "v1", "SIDEBAR", DAY).usedPlacement());
        assertEquals(2, service.queryQuota("c1", null, null, DAY).usedTotal());
        // 另一访客不受影响
        ReservationResponse other = service.applyPlacement(
                new ApplyPlacementExposureRequest("a4", "c1", "BANNER", "v2"));
        assertEquals("RESERVED", other.status().name());
    }

    @Test
    @DisplayName("展示位额度已满返回 429，公告与访客层均不增加")
    void placementCapFull_returns429AndNoLayerIncrease() {
        createCampaign("req-c", "c1", 100, 100);
        addPlacement("req-p", "c1", "P", 2, 1);
        service.applyPlacement(new ApplyPlacementExposureRequest("a1", "c1", "P", "v1"));
        service.applyPlacement(new ApplyPlacementExposureRequest("a2", "c1", "P", "v2"));

        assertStatus(429, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("a3", "c1", "P", "v3")));

        // 展示位 2、总额 2，v3 访客 0；三层均未因失败而增加
        QuotaResponse q = service.queryQuota("c1", "v3", "P", DAY);
        assertEquals(2, q.usedTotal());
        assertEquals(0, q.usedVisitor());
        assertEquals(2, q.usedPlacement());
    }

    @Test
    @DisplayName("不存在的展示位申请返回 404")
    void applyPlacement_unknown_returns404() {
        createCampaign("req-c", "c1", 100, 5);
        assertStatus(404, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "NOPE", "v1")));
    }

    @Test
    @DisplayName("取消按展示位预占：同时释放公告/访客/展示位三层额度")
    void cancel_releasesThreeLayers() {
        createCampaign("req-c", "c1", 100, 2);
        addPlacement("req-p", "c1", "P", 1, 1);
        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "P", "v1"));

        service.cancel(r.reservationId(), new ReservationActionRequest("x1"));

        QuotaResponse q = service.queryQuota("c1", "v1", "P", DAY);
        assertEquals(0, q.usedTotal());
        assertEquals(0, q.usedVisitor());
        assertEquals(0, q.usedPlacement());
        assertEquals(1, q.remainingPlacement());
    }

    @Test
    @DisplayName("到期结算释放三层额度；过期单确认/取消均 409")
    void expiry_releasesThreeLayers() {
        createCampaign("req-c", "c1", 100, 2);
        addPlacement("req-p", "c1", "P", 1, 1);
        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "P", "v1"));

        mutableClock().advanceMillis(60_000L);
        assertStatus(409, () -> service.confirm(r.reservationId(), new ReservationActionRequest("k1")));
        assertStatus(409, () -> service.cancel(r.reservationId(), new ReservationActionRequest("x1")));

        QuotaResponse q = service.queryQuota("c1", "v1", "P", DAY);
        assertEquals(0, q.usedTotal());
        assertEquals(0, q.usedVisitor());
        assertEquals(0, q.usedPlacement());
        // 释放后同展示位可再次申请
        ReservationResponse again = service.applyPlacement(
                new ApplyPlacementExposureRequest("a2", "c1", "P", "v1"));
        assertEquals("RESERVED", again.status().name());
    }

    @Test
    @DisplayName("确认后持续占用三层；跨日确认不迁移，预占固定原展示位与原 UTC 日")
    void confirm_keepsThreeLayersAndDoesNotMigrateAcrossDay() {
        mutableClock().setInstant(Instant.parse("2026-09-23T23:59:30Z"));
        createCampaign("req-c", "c1", 100, 2);
        addPlacement("req-p", "c1", "P", 5, 1);
        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "P", "v1"));
        assertEquals(LocalDate.of(2026, 9, 23), r.utcDate());
        assertEquals("P", r.placementCode());

        mutableClock().setInstant(Instant.parse("2026-09-24T00:00:01Z"));
        ReservationResponse confirmed = service.confirm(r.reservationId(),
                new ReservationActionRequest("k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals("P", confirmed.placementCode());

        // 原 UTC 日三层仍占用
        QuotaResponse old = service.queryQuota("c1", "v1", "P", LocalDate.of(2026, 9, 23));
        assertEquals(1, old.usedTotal());
        assertEquals(1, old.usedVisitor());
        assertEquals(1, old.usedPlacement());
        // 新 UTC 日三层全新
        QuotaResponse next = service.queryQuota("c1", "v1", "P", LocalDate.of(2026, 9, 24));
        assertEquals(0, next.usedTotal());
        assertEquals(0, next.usedPlacement());
    }

    @Test
    @DisplayName("三层额度与预占展示位明细查询：按公告/访客/展示位/UTC 日过滤")
    void quotaDetail_returnsThreeLayersAndReservationDetails() {
        createCampaign("req-c", "c1", 100, 5);
        addPlacement("req-p1", "c1", "P1", 10, 1);
        addPlacement("req-p2", "c1", "P2", 10, 2);
        ReservationResponse r1 = service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "P1", "v1"));
        ReservationResponse r2 = service.applyPlacement(
                new ApplyPlacementExposureRequest("a2", "c1", "P2", "v1"));
        service.applyPlacement(new ApplyPlacementExposureRequest("a3", "c1", "P1", "v2"));

        // 公告维度：列出当天全部 3 条预占
        QuotaDetailResponse all = service.queryQuotaDetail("c1", null, null, DAY);
        assertEquals(3, all.reservations().size());
        assertNull(all.quota().usedVisitor());

        // 公告+访客：v1 的 2 条，跨 P1/P2
        QuotaDetailResponse byVisitor = service.queryQuotaDetail("c1", "v1", null, DAY);
        assertEquals(2, byVisitor.reservations().size());
        assertEquals(2, byVisitor.quota().usedVisitor());
        assertNull(byVisitor.quota().placementCode());

        // 公告+访客+展示位：预占明细仅 v1 在 P1 的 1 条；
        // 注意展示位账目跨访客计数，P1 上还有 v2 的 1 条，故 usedPlacement=2
        QuotaDetailResponse byPlacement = service.queryQuotaDetail("c1", "v1", "P1", DAY);
        assertEquals(1, byPlacement.reservations().size());
        assertEquals(r1.reservationId(), byPlacement.reservations().get(0).reservationId());
        assertEquals(2, byPlacement.quota().usedPlacement());
        // 访客账跨展示位共享，v1 在 P1、P2 各 1，合计 2
        assertEquals(2, byPlacement.quota().usedVisitor());

        // 公告+展示位（不限访客）：P1 有 2 条
        QuotaDetailResponse placementOnly = service.queryQuotaDetail("c1", null, "P1", DAY);
        assertEquals(2, placementOnly.reservations().size());
        assertTrue(placementOnly.reservations().stream()
                .allMatch(x -> x.placementCode().equals("P1")));
        assertEquals(r2.placementCode(), "P2");
    }

    @Test
    @DisplayName("按展示位申请幂等：同键同参重放原预占；异参 409；429 失败不占键")
    void applyPlacement_idempotent_replayConflictFailureNoKey() {
        createCampaign("req-c", "c1", 100, 5);
        addPlacement("req-p", "c1", "P", 1, 1);
        ReservationResponse first = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P", "v1"));

        ReservationResponse replay = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P", "v1"));
        assertEquals(first.reservationId(), replay.reservationId());

        // 同键异参（不同展示位）→ 409
        assertStatus(409, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "DEFAULT", "v1")));
        // 同键异参（不同访客）→ 409
        assertStatus(409, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-1", "c1", "P", "v2")));

        // 展示位额度仅 1，第二个不同访客申请 429；失败不占键
        assertStatus(429, () -> service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P", "v9")));
        // 取消释放后同键同参可成功
        service.cancel(first.reservationId(), new ReservationActionRequest("x1"));
        ReservationResponse retried = service.applyPlacement(
                new ApplyPlacementExposureRequest("key-fail", "c1", "P", "v9"));
        assertEquals("RESERVED", retried.status().name());
    }

    @Test
    @DisplayName("新增展示位幂等：同键同参重放原结果；异参 409")
    void createPlacement_idempotent() {
        createCampaign("req-c", "c1", 100, 5);
        PlacementResponse first = addPlacement("key-p", "c1", "P", 10, 1);
        PlacementResponse replay = addPlacement("key-p", "c1", "P", 10, 1);
        assertEquals(first.configVersion(), replay.configVersion());
        // 版本只递增一次
        assertEquals(2, service.listPlacements("c1").size());
        // 同键异参（不同 cap）→ 409
        assertStatus(409, () -> addPlacement("key-p", "c1", "P", 20, 1));
    }

    @Test
    @DisplayName("已创建预占绑定申请时的展示位，不受后来新增展示位影响")
    void existingReservation_unaffectedByLaterPlacement() {
        createCampaign("req-c", "c1", 100, 5);
        ReservationResponse r = service.applyPlacement(
                new ApplyPlacementExposureRequest("a1", "c1", "DEFAULT", "v1"));
        // 申请之后新增展示位并推进版本
        addPlacement("req-p", "c1", "LATER", 10, 1);

        ReservationResponse detail = service.getReservation(r.reservationId());
        assertEquals("DEFAULT", detail.placementCode());
        // 取消旧预占只释放 DEFAULT 层，不触碰 LATER
        service.cancel(r.reservationId(), new ReservationActionRequest("x1"));
        assertEquals(0, service.queryQuota("c1", "v1", "DEFAULT", DAY).usedPlacement());
        assertEquals(0, service.queryQuota("c1", "v1", "LATER", DAY).usedPlacement());
    }

    @Test
    @DisplayName("旧额度查询（不传 placementCode）响应兼容：展示位字段为 null")
    void legacyQuotaQuery_compatibleNullPlacementFields() {
        createCampaign("req-c", "c1", 100, 5);
        service.apply(new ApplyExposureRequest("a1", "c1", "v1"));

        QuotaResponse visitorOnly = service.queryQuota("c1", "v1", null, DAY);
        assertEquals(1, visitorOnly.usedVisitor());
        assertNull(visitorOnly.placementCode());
        assertNull(visitorOnly.placementDailyCap());
        assertNull(visitorOnly.usedPlacement());
        assertNull(visitorOnly.remainingPlacement());

        QuotaResponse totalOnly = service.queryQuota("c1", null, null, DAY);
        assertNull(totalOnly.usedVisitor());
        assertNull(totalOnly.usedPlacement());
    }

    @Test
    @DisplayName("HTTP 语义：新增展示位 201、按位申请 201、展示位满 429、版本不符 409、参数越界 400、明细查询")
    void httpSemantics_placementEndpoints() throws Exception {
        // 创建公告
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":2,"
                                + "\"perVisitorDailyCap\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.configVersion").value(1));

        // DEFAULT 自动存在；新增额度为 2 的展示位 BANNER，expectedConfigVersion=1
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"placementCode\":\"BANNER\",\"dailyCap\":2,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("BANNER"))
                .andExpect(jsonPath("$.configVersion").value(2));

        // 版本不符：当前已为 2，仍传 1 -> 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"placementCode\":\"SIDE\",\"dailyCap\":2,"
                                + "\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        // 展示位列表
        mockMvc.perform(get("/api/exposure/campaigns/ch/placements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // 按展示位申请两次（不同访客），占满 BANNER 额度 2
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"BANNER\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.placementCode").value("BANNER"))
                .andExpect(jsonPath("$.status").value("RESERVED"));
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"BANNER\",\"visitorId\":\"u2\"}"))
                .andExpect(status().isCreated());

        // 展示位额度已满 -> 429
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"BANNER\",\"visitorId\":\"u3\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // 不存在展示位 -> 404
        mockMvc.perform(post("/api/exposure/placement-reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"campaignId\":\"ch\","
                                + "\"placementCode\":\"NOPE\",\"visitorId\":\"u3\"}"))
                .andExpect(status().isNotFound());

        // dailyCap 越界（0）-> 400
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h8\",\"placementCode\":\"BAD\",\"dailyCap\":0,"
                                + "\"expectedConfigVersion\":2}"))
                .andExpect(status().isBadRequest());

        // dailyCap 超过公告总额 -> 400
        mockMvc.perform(post("/api/exposure/campaigns/ch/placements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h9\",\"placementCode\":\"BIG\",\"dailyCap\":3,"
                                + "\"expectedConfigVersion\":2}"))
                .andExpect(status().isBadRequest());

        // 三层额度查询 JSON
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("placementCode", "BANNER")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTotal").value(2))
                .andExpect(jsonPath("$.usedVisitor").value(1))
                .andExpect(jsonPath("$.usedPlacement").value(2))
                .andExpect(jsonPath("$.placementDailyCap").value(2));

        // 明细查询：BANNER 有 2 条预占
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota/detail")
                        .param("placementCode", "BANNER")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations.length()").value(2))
                .andExpect(jsonPath("$.quota.usedPlacement").value(2));

        // 旧额度查询（不传 placementCode）：展示位维度字段为 null，旧字段保持可用
        mockMvc.perform(get("/api/exposure/campaigns/ch/quota")
                        .param("visitorId", "u1")
                        .param("utcDate", "2026-09-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedVisitor").value(1))
                .andExpect(jsonPath("$.placementCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.usedPlacement").value(org.hamcrest.Matchers.nullValue()));
    }

    private void assertStatus(int expected, Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(expected, ex.getStatus().value());
    }
}
