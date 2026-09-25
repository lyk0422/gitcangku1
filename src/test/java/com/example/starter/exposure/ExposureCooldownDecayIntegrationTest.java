package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CooldownException;
import com.example.starter.exposure.web.CooldownStatusResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecayRecordResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCooldownRequest;
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

import java.math.BigDecimal;
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
 * 冷却期限流与频次衰减 H2（MODE=MySQL）集成测试：
 * 冷却判定、衰减计数、跨日重置、冷却配置版本冲突与查询端点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExposureCooldownDecayIntegrationTest {

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
    static final LocalDate DAY1 = LocalDate.of(2026, 9, 22);
    static final LocalDate DAY2 = LocalDate.of(2026, 9, 23);

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
        jdbc.update("DELETE FROM exposure_decay_record");
        jdbc.update("DELETE FROM visitor_campaign_cooldown");
        jdbc.update("DELETE FROM exposure_reservation");
        jdbc.update("DELETE FROM quota_visitor_ledger");
        jdbc.update("DELETE FROM quota_total_ledger");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor,
                                Integer cooldownMinutes) {
        service.createCampaign(
                new CreateCampaignRequest(requestId, campaignId, total, perVisitor, cooldownMinutes));
    }

    private ReservationResponse applyAndConfirm(String requestPrefix, String campaignId, String visitorId) {
        ReservationResponse r = service.apply(
                new ApplyExposureRequest(requestPrefix + "-a", campaignId, visitorId));
        return service.confirm(r.reservationId(), new ReservationActionRequest(requestPrefix + "-c"));
    }

    @Test
    @DisplayName("冷却期内申请返回 429 并携带冷却结束时刻：不创建预占、不占用额度")
    void cooldownActive_applyRejected429_noReservationNoQuota() {
        createCampaign("req-c1", "c1", 10, 10, 30);
        applyAndConfirm("req-1", "c1", "v1");

        mutableClock().advanceMillis(10 * 60_000L);
        CooldownException ex = assertThrows(CooldownException.class,
                () -> service.apply(new ApplyExposureRequest("req-2-a", "c1", "v1")));
        assertEquals(429, ex.getStatus().value());
        long expectedUntil = BASE.toEpochMilli() + 30 * 60_000L;
        assertEquals(expectedUntil, ex.getCooldownUntilUtc());

        // 未创建新预占、未占用额度
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'c1'", Integer.class));
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY1);
        assertEquals(1, quota.usedTotal());
        assertEquals(1, quota.usedVisitor());

        // 其他访客不受该访客冷却影响
        ReservationResponse other = service.apply(new ApplyExposureRequest("req-3-a", "c1", "v2"));
        assertEquals("RESERVED", other.status().name());
    }

    @Test
    @DisplayName("冷却结束时刻到达后可再次申请；冷却分钟为 0 不限制")
    void cooldownElapsed_applyAllowed_andZeroCooldownUnlimited() {
        createCampaign("req-c1", "c1", 10, 10, 30);
        applyAndConfirm("req-1", "c1", "v1");

        // 恰好到达冷却结束时刻：已过冷却期
        mutableClock().setInstant(BASE.plusMillis(30 * 60_000L));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-2-a", "c1", "v1"));
        assertEquals("RESERVED", r.status().name());

        // 冷却分钟为 0：连续确认后立即申请不拦截
        createCampaign("req-c2", "c2", 10, 10, 0);
        applyAndConfirm("req-3", "c2", "v1");
        ReservationResponse again = service.apply(new ApplyExposureRequest("req-4-a", "c2", "v1"));
        assertEquals("RESERVED", again.status().name());
    }

    @Test
    @DisplayName("取消与过期不更新最近确认时刻，冷却只由 CONFIRMED 触发")
    void cancelAndExpiry_doNotUpdateCooldown() {
        createCampaign("req-c1", "c1", 10, 10, 30);

        // 取消：不产生冷却
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-1-a", "c1", "v1"));
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-1-x"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-2-a", "c1", "v1"));
        assertEquals("RESERVED", r2.status().name());

        // 过期：不产生冷却
        mutableClock().advanceMillis(60_000L);
        assert409(() -> service.confirm(r2.reservationId(), new ReservationActionRequest("req-2-c")));
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-3-a", "c1", "v1"));
        assertEquals("RESERVED", r3.status().name());

        CooldownStatusResponse status = service.queryCooldownStatus("c1", "v1");
        assertNull(status.lastConfirmedAtUtc());
        assertNull(status.cooldownUntilUtc());
        assertFalse(status.inCooldown());
        assertEquals(0, service.queryDecayRecords("c1", "v1", DAY1).size());
    }

    @Test
    @DisplayName("冷却只拦截申请：已存在 RESERVED 的确认与取消不受影响")
    void cooldown_doesNotAffectExistingReservedConfirmCancel() {
        createCampaign("req-c1", "c1", 10, 10, 60);
        // 冷却开始前已存在两个 RESERVED 预占
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-1-a", "c1", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-2-a", "c1", "v1"));

        // 确认 r1 后进入冷却期
        assertEquals("CONFIRMED",
                service.confirm(r1.reservationId(), new ReservationActionRequest("req-1-c")).status().name());

        // 新申请被冷却拦截
        assertThrows(CooldownException.class,
                () -> service.apply(new ApplyExposureRequest("req-3-a", "c1", "v1")));

        // 但冷却开始前已存在的 r2 仍可确认（冷却只在申请阶段拦截）
        assertEquals("CONFIRMED",
                service.confirm(r2.reservationId(), new ReservationActionRequest("req-2-c")).status().name());

        // 已存在预占的取消同样不受冷却影响
        createCampaign("req-c2", "c2", 10, 10, 60);
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-4-a", "c2", "v2"));
        ReservationResponse r4 = service.apply(new ApplyExposureRequest("req-5-a", "c2", "v2"));
        service.confirm(r3.reservationId(), new ReservationActionRequest("req-4-c"));
        assertEquals("CANCELLED",
                service.cancel(r4.reservationId(), new ReservationActionRequest("req-5-x")).status().name());
    }

    @Test
    @DisplayName("衰减权重：当日第 N 次确认权重为 1/N（4 位小数 HALF_UP），跨日重新从 1 计数且历史保留")
    void decayWeights_sequentialAndResetAcrossDays() {
        createCampaign("req-c1", "c1", 100, 100, 0);

        applyAndConfirm("req-1", "c1", "v1");
        mutableClock().advanceMillis(1000L);
        applyAndConfirm("req-2", "c1", "v1");
        mutableClock().advanceMillis(1000L);
        applyAndConfirm("req-3", "c1", "v1");

        List<DecayRecordResponse> day1 = service.queryDecayRecords("c1", "v1", DAY1);
        assertEquals(3, day1.size());
        assertEquals(1, day1.get(0).seqNo());
        assertEquals(new BigDecimal("1.0000"), day1.get(0).decayWeight());
        assertEquals(2, day1.get(1).seqNo());
        assertEquals(new BigDecimal("0.5000"), day1.get(1).decayWeight());
        assertEquals(3, day1.get(2).seqNo());
        assertEquals(new BigDecimal("0.3333"), day1.get(2).decayWeight());

        // 跨零点：重新从 1 计数
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:01Z"));
        applyAndConfirm("req-4", "c1", "v1");
        List<DecayRecordResponse> day2 = service.queryDecayRecords("c1", "v1", DAY2);
        assertEquals(1, day2.size());
        assertEquals(1, day2.get(0).seqNo());
        assertEquals(new BigDecimal("1.0000"), day2.get(0).decayWeight());

        // 历史保留
        assertEquals(3, service.queryDecayRecords("c1", "v1", DAY1).size());
    }

    @Test
    @DisplayName("重复确认（同键重放与新键同类终态）不重复写入衰减记录与冷却时刻")
    void repeatedConfirm_doesNotDuplicateDecayOrCooldown() {
        createCampaign("req-c1", "c1", 10, 10, 30);
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-1-a", "c1", "v1"));
        service.confirm(r.reservationId(), new ReservationActionRequest("req-1-c"));

        mutableClock().advanceMillis(5000L);
        // 同键重放
        service.confirm(r.reservationId(), new ReservationActionRequest("req-1-c"));
        // 新键同类终态
        service.confirm(r.reservationId(), new ReservationActionRequest("req-1-c2"));

        List<DecayRecordResponse> records = service.queryDecayRecords("c1", "v1", DAY1);
        assertEquals(1, records.size());
        assertEquals(BASE.toEpochMilli(), records.get(0).confirmedAtUtc());
        CooldownStatusResponse status = service.queryCooldownStatus("c1", "v1");
        assertEquals(BASE.toEpochMilli(), status.lastConfirmedAtUtc());
    }

    @Test
    @DisplayName("冷却配置修改：expectedVersion 冲突 409，成功后版本 +1 且只影响后续申请")
    void updateCooldown_versionConflictAndEffect() {
        createCampaign("req-c1", "c1", 10, 10, 0);
        // 初始版本为 0
        CampaignResponse updated = service.updateCooldown("c1",
                new UpdateCooldownRequest("req-u1", 45, 0L));
        assertEquals(45, updated.cooldownMinutes());
        assertEquals(1L, updated.version());

        // 旧版本号再次修改 → 409
        assert409(() -> service.updateCooldown("c1", new UpdateCooldownRequest("req-u2", 10, 0L)));
        // 当前版本号修改成功
        CampaignResponse updated2 = service.updateCooldown("c1",
                new UpdateCooldownRequest("req-u3", 10, 1L));
        assertEquals(10, updated2.cooldownMinutes());
        assertEquals(2L, updated2.version());

        // 只影响后续申请：修改前的确认时刻 + 新冷却分钟数用于后续判定
        createCampaign("req-c2", "c2", 10, 10, 0);
        applyAndConfirm("req-1", "c2", "v1");
        service.updateCooldown("c2", new UpdateCooldownRequest("req-u4", 30, 0L));
        mutableClock().advanceMillis(10 * 60_000L);
        CooldownException ex = assertThrows(CooldownException.class,
                () -> service.apply(new ApplyExposureRequest("req-5-a", "c2", "v1")));
        assertEquals(BASE.toEpochMilli() + 10 * 60_000L + 20 * 60_000L, ex.getCooldownUntilUtc());

        // 历史衰减记录不改写
        assertEquals(1, service.queryDecayRecords("c2", "v1", DAY1).size());
    }

    @Test
    @DisplayName("冷却配置修改幂等：同键同参重放原结果，异参 409，失败不占键")
    void updateCooldown_idempotency() {
        createCampaign("req-c1", "c1", 10, 10, 0);
        CampaignResponse first = service.updateCooldown("c1",
                new UpdateCooldownRequest("key-u", 20, 0L));

        CampaignResponse replay = service.updateCooldown("c1",
                new UpdateCooldownRequest("key-u", 20, 0L));
        assertEquals(first.version(), replay.version());
        assertEquals(first.cooldownMinutes(), replay.cooldownMinutes());

        // 同键异参 → 409
        assert409(() -> service.updateCooldown("c1", new UpdateCooldownRequest("key-u", 30, 0L)));

        // 失败（版本冲突）不占键：修正参数后同键可成功
        assert409(() -> service.updateCooldown("c1", new UpdateCooldownRequest("key-f", 30, 0L)));
        CampaignResponse retried = service.updateCooldown("c1",
                new UpdateCooldownRequest("key-f", 30, 1L));
        assertEquals(30, retried.cooldownMinutes());
    }

    @Test
    @DisplayName("冷却状态查询：配置、最近确认时刻、冷却结束时刻与是否在冷却期")
    void cooldownStatusQuery_reflectsConfirmAndClock() {
        createCampaign("req-c1", "c1", 10, 10, 30);

        CooldownStatusResponse before = service.queryCooldownStatus("c1", "v1");
        assertEquals(30, before.cooldownMinutes());
        assertNull(before.lastConfirmedAtUtc());
        assertNull(before.cooldownUntilUtc());
        assertFalse(before.inCooldown());
        assertEquals(BASE.toEpochMilli(), before.checkedAtUtc());

        applyAndConfirm("req-1", "c1", "v1");
        mutableClock().advanceMillis(10 * 60_000L);

        CooldownStatusResponse during = service.queryCooldownStatus("c1", "v1");
        assertEquals(BASE.toEpochMilli(), during.lastConfirmedAtUtc());
        assertEquals(BASE.toEpochMilli() + 30 * 60_000L, during.cooldownUntilUtc());
        assertTrue(during.inCooldown());

        mutableClock().advanceMillis(20 * 60_000L);
        CooldownStatusResponse after = service.queryCooldownStatus("c1", "v1");
        assertFalse(after.inCooldown());
        assertEquals(BASE.toEpochMilli() + 30 * 60_000L, after.cooldownUntilUtc());

        assert404(() -> service.queryCooldownStatus("nope", "v1"));
    }

    @Test
    @DisplayName("HTTP 语义：冷却 429 携带冷却结束时刻；冷却配置修改与查询端点")
    void httpSemantics_cooldownAndDecayEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10,\"cooldownMinutes\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cooldownMinutes").value(30))
                .andExpect(jsonPath("$.version").value(0));

        String applyBody = "{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}";
        String reservationId = objectMapper.readTree(mockMvc.perform(
                        post("/api/exposure/reservations")
                                .contentType(MediaType.APPLICATION_JSON).content(applyBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("reservationId").asText();

        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 冷却期内申请 → 429 且携带冷却结束时刻
        long expectedUntil = BASE.toEpochMilli() + 30 * 60_000L;
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.cooldownUntilUtc").value(expectedUntil));

        // 冷却状态查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/cooldown").param("visitorId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownMinutes").value(30))
                .andExpect(jsonPath("$.lastConfirmedAtUtc").value(BASE.toEpochMilli()))
                .andExpect(jsonPath("$.cooldownUntilUtc").value(expectedUntil))
                .andExpect(jsonPath("$.inCooldown").value(true));

        // 衰减明细查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/decay")
                        .param("visitorId", "u1").param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seqNo").value(1))
                .andExpect(jsonPath("$[0].decayWeight").value(1.0))
                .andExpect(jsonPath("$[0].reservationId").value(reservationId));

        // 冷却配置修改：版本冲突 409、参数越界 400、成功 200
        mockMvc.perform(post("/api/exposure/campaigns/ch/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"cooldownMinutes\":60,\"expectedVersion\":5}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/exposure/campaigns/ch/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"cooldownMinutes\":1441,\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/exposure/campaigns/ch/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"cooldownMinutes\":60,\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownMinutes").value(60))
                .andExpect(jsonPath("$.version").value(1));
    }

    private void assert409(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
