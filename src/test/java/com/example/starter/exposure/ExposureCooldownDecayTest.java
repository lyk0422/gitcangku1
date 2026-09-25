package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CooldownNotElapsedException;
import com.example.starter.exposure.web.CooldownStatusResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.DecayRecordResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateCooldownRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冷却期限流与频次衰减 H2（MODE=MySQL）集成测试：
 * 冷却判定边界、配置版本冲突、衰减权重计数、跨日重置与查询端点。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExposureCooldownDecayTest {

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
        jdbc.update("DELETE FROM exposure_decay_record");
        jdbc.update("DELETE FROM visitor_last_confirmation");
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

    private ReservationResponse applyAndConfirm(String campaignId, String visitorId, String keyPrefix) {
        ReservationResponse r = service.apply(
                new ApplyExposureRequest(keyPrefix + "-a", campaignId, visitorId));
        return service.confirm(r.reservationId(), new ReservationActionRequest(keyPrefix + "-k"));
    }

    @Test
    @DisplayName("冷却期未过申请返回 429 并携带冷却结束时刻：不占额度、不占幂等键，到期边界可申请")
    void cooldownBlocksReapplyUntilElapsed() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cd1", 10, 10, 10));
        applyAndConfirm("cd1", "v1", "req-1");
        long confirmAt = BASE.toEpochMilli();
        long until = confirmAt + 10 * 60_000L;

        // 冷却期内再申请 → 429 携带冷却结束 UTC 时刻
        CooldownNotElapsedException ex = assertThrows(CooldownNotElapsedException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "cd1", "v1")));
        assertEquals(429, ex.getStatus().value());
        assertEquals(until, ex.getCooldownUntilUtc());

        // 不创建预占、不占用当日额度
        assertEquals(1, service.queryQuota("cd1", "v1", DAY).usedTotal());
        assertEquals(1, service.queryQuota("cd1", "v1", DAY).usedVisitor());

        // 推进 9 分钟仍未过冷却期
        mutableClock().advanceMillis(9 * 60_000L);
        CooldownNotElapsedException stillCooling = assertThrows(CooldownNotElapsedException.class,
                () -> service.apply(new ApplyExposureRequest("req-a3", "cd1", "v1")));
        assertEquals(until, stillCooling.getCooldownUntilUtc());

        // 推进到恰好冷却结束时刻：边界放行；且此前失败的 req-a2 未占幂等键，可同键同参成功
        mutableClock().advanceMillis(60_000L);
        ReservationResponse retried = service.apply(new ApplyExposureRequest("req-a2", "cd1", "v1"));
        assertEquals("RESERVED", retried.status().name());
        assertEquals(2, service.queryQuota("cd1", "v1", DAY).usedTotal());
    }

    @Test
    @DisplayName("冷却分钟数为 0 表示不限制：确认后可立即再次申请")
    void cooldownZeroMeansNoRestriction() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cd0", 10, 10, 0));
        applyAndConfirm("cd0", "v1", "req-1");

        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a2", "cd0", "v1"));
        assertEquals("RESERVED", r.status().name());

        CooldownStatusResponse status = service.queryCooldown("cd0", "v1");
        assertEquals(0, status.cooldownMinutes());
        assertEquals(BASE.toEpochMilli(), status.lastConfirmedAtUtc());
        assertNull(status.cooldownUntilUtc());
        assertTrue(!status.cooling());
    }

    @Test
    @DisplayName("取消与过期的预占不更新最近确认时刻，不触发冷却")
    void cancelAndExpireDoNotTriggerCooldown() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cd2", 10, 10, 10));

        // 取消：不产生冷却
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cd2", "v1"));
        service.cancel(r1.reservationId(), new ReservationActionRequest("req-x1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cd2", "v1"));
        assertEquals("RESERVED", r2.status().name());

        // 过期：不产生冷却（推进 60 秒到期并结算）
        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getReservation(r2.reservationId()).status().name());
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "cd2", "v1"));
        assertEquals("RESERVED", r3.status().name());

        // 最近确认时刻仍为空，冷却状态不拦截
        CooldownStatusResponse status = service.queryCooldown("cd2", "v1");
        assertNull(status.lastConfirmedAtUtc());
        assertTrue(!status.cooling());
        assertEquals(0, service.queryDecay("cd2", "v1", DAY).size());
    }

    @Test
    @DisplayName("冷却只拦截申请：已存在 RESERVED 的确认与取消不受影响")
    void cooldownDoesNotAffectExistingReserved() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cd3", 10, 10, 10));
        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "cd3", "v1"));
        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "cd3", "v1"));

        // 确认 r1 后冷却生效，但 r2 的确认与取消不受冷却拦截
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        ReservationResponse confirmed2 = service.confirm(r2.reservationId(),
                new ReservationActionRequest("req-k2"));
        assertEquals("CONFIRMED", confirmed2.status().name());

        // 冷却期内申请被拦截，但取消已确认单之外的 RESERVED 仍可用（另建访客验证取消路径）
        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "cd3", "v2"));
        ReservationResponse cancelled = service.cancel(r3.reservationId(),
                new ReservationActionRequest("req-x3"));
        assertEquals("CANCELLED", cancelled.status().name());
    }

    @Test
    @DisplayName("冷却配置修改：expectedVersion 冲突 409，同键重放原结果，异参 409，只影响后续申请")
    void updateCooldown_versionConflictAndIdempotency() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cfg", 10, 10, 5));

        // 版本不符 → 409，配置不变
        assert409(() -> service.updateCooldown("cfg", new UpdateCooldownRequest("req-u1", 1L, 30)));

        CampaignResponse updated = service.updateCooldown("cfg",
                new UpdateCooldownRequest("req-u2", 0L, 30));
        assertEquals(30, updated.cooldownMinutes());
        assertEquals(1, updated.version());

        // 同 requestId 同参重放：返回首次结果，版本不重复 +1
        CampaignResponse replay = service.updateCooldown("cfg",
                new UpdateCooldownRequest("req-u2", 0L, 30));
        assertEquals(1, replay.version());
        assertEquals(30, replay.cooldownMinutes());

        // 同 requestId 异参 → 409
        assert409(() -> service.updateCooldown("cfg", new UpdateCooldownRequest("req-u2", 0L, 60)));
        // 旧版本再次修改 → 409
        assert409(() -> service.updateCooldown("cfg", new UpdateCooldownRequest("req-u3", 0L, 60)));

        CampaignResponse v2 = service.updateCooldown("cfg", new UpdateCooldownRequest("req-u4", 1L, 0));
        assertEquals(0, v2.cooldownMinutes());
        assertEquals(2, v2.version());

        assert404(() -> service.updateCooldown("nope", new UpdateCooldownRequest("req-u5", 0L, 0)));
    }

    @Test
    @DisplayName("冷却配置修改只影响后续申请：历史确认时刻参与判定，衰减记录不改写")
    void cooldownConfigAppliesToFutureAppliesOnly() {
        service.createCampaign(new CreateCampaignRequest("req-c", "cfg2", 10, 10, 0));
        applyAndConfirm("cfg2", "v1", "req-1");
        assertEquals(1, service.queryDecay("cfg2", "v1", DAY).size());

        // 开启 10 分钟冷却：基于历史确认时刻拦截后续申请
        service.updateCooldown("cfg2", new UpdateCooldownRequest("req-u1", 0L, 10));
        CooldownNotElapsedException ex = assertThrows(CooldownNotElapsedException.class,
                () -> service.apply(new ApplyExposureRequest("req-a2", "cfg2", "v1")));
        assertEquals(BASE.toEpochMilli() + 10 * 60_000L, ex.getCooldownUntilUtc());

        // 关闭冷却：立即放行
        service.updateCooldown("cfg2", new UpdateCooldownRequest("req-u2", 1L, 0));
        ReservationResponse r = service.apply(new ApplyExposureRequest("req-a3", "cfg2", "v1"));
        assertEquals("RESERVED", r.status().name());

        // 历史衰减记录不改写
        List<DecayRecordResponse> decay = service.queryDecay("cfg2", "v1", DAY);
        assertEquals(1, decay.size());
        assertEquals(1, decay.get(0).sequenceNo());
    }

    @Test
    @DisplayName("衰减权重：当日第 N 次确认记录 1/N（4 位小数 HALF_UP），按序可查")
    void decayWeightsSequenceAndRounding() {
        service.createCampaign(new CreateCampaignRequest("req-c", "dc", 100, 100, 0));
        for (int i = 1; i <= 4; i++) {
            applyAndConfirm("dc", "v1", "req-" + i);
        }

        List<DecayRecordResponse> decay = service.queryDecay("dc", "v1", DAY);
        assertEquals(4, decay.size());
        BigDecimal[] expected = {
                new BigDecimal("1.0000"), new BigDecimal("0.5000"),
                new BigDecimal("0.3333"), new BigDecimal("0.2500")};
        for (int i = 0; i < 4; i++) {
            DecayRecordResponse record = decay.get(i);
            assertEquals(i + 1, record.sequenceNo());
            assertEquals(0, expected[i].compareTo(record.decayWeight()),
                    "第 " + (i + 1) + " 次权重应为 " + expected[i]);
            assertEquals("dc", record.campaignId());
            assertEquals("v1", record.visitorId());
            assertEquals(DAY, record.utcDate());
            assertEquals(BASE.toEpochMilli(), record.confirmedAtUtc());
        }
    }

    @Test
    @DisplayName("跨 UTC 零点衰减重新从 1 计数，历史日记录保留")
    void decayResetsAcrossUtcDays() {
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:40Z"));
        service.createCampaign(new CreateCampaignRequest("req-c", "dd", 100, 100, 0));
        applyAndConfirm("dd", "v1", "req-1");
        mutableClock().setInstant(Instant.parse("2026-09-22T23:59:50Z"));
        applyAndConfirm("dd", "v1", "req-2");

        // 跨零点：确认落在次日，序号重新从 1 开始
        mutableClock().setInstant(Instant.parse("2026-09-23T00:00:10Z"));
        applyAndConfirm("dd", "v1", "req-3");

        List<DecayRecordResponse> day1 = service.queryDecay("dd", "v1", LocalDate.of(2026, 9, 22));
        assertEquals(2, day1.size());
        assertEquals(1, day1.get(0).sequenceNo());
        assertEquals(2, day1.get(1).sequenceNo());
        assertEquals(0, new BigDecimal("0.5000").compareTo(day1.get(1).decayWeight()));

        List<DecayRecordResponse> day2 = service.queryDecay("dd", "v1", LocalDate.of(2026, 9, 23));
        assertEquals(1, day2.size());
        assertEquals(1, day2.get(0).sequenceNo());
        assertEquals(0, new BigDecimal("1.0000").compareTo(day2.get(0).decayWeight()));
    }

    @Test
    @DisplayName("重复确认不重复写衰减记录；取消与过期不产生衰减记录")
    void noDuplicateDecayAndNoDecayOnCancelOrExpire() {
        service.createCampaign(new CreateCampaignRequest("req-c", "nd", 10, 10, 0));

        ReservationResponse r1 = service.apply(new ApplyExposureRequest("req-a1", "nd", "v1"));
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));
        // 新 requestId 的重复确认：返回原状态，不新增衰减记录
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1b"));
        // 同 requestId 重放：同样不新增
        service.confirm(r1.reservationId(), new ReservationActionRequest("req-k1"));

        ReservationResponse r2 = service.apply(new ApplyExposureRequest("req-a2", "nd", "v1"));
        service.cancel(r2.reservationId(), new ReservationActionRequest("req-x2"));

        ReservationResponse r3 = service.apply(new ApplyExposureRequest("req-a3", "nd", "v1"));
        mutableClock().advanceMillis(60_000L);
        assertEquals("EXPIRED", service.getReservation(r3.reservationId()).status().name());

        List<DecayRecordResponse> decay = service.queryDecay("nd", "v1", DAY);
        assertEquals(1, decay.size());
        assertEquals(1, decay.get(0).sequenceNo());
        assertEquals(r1.reservationId(), decay.get(0).reservationId());
    }

    @Test
    @DisplayName("冷却状态查询：确认前无记录，确认后按注入时钟判定冷却中/已结束")
    void cooldownStatusQueryReflectsClock() {
        service.createCampaign(new CreateCampaignRequest("req-c", "st", 10, 10, 10));

        CooldownStatusResponse before = service.queryCooldown("st", "v1");
        assertNull(before.lastConfirmedAtUtc());
        assertNull(before.cooldownUntilUtc());
        assertTrue(!before.cooling());
        assertEquals(BASE.toEpochMilli(), before.checkedAtUtc());

        applyAndConfirm("st", "v1", "req-1");
        CooldownStatusResponse cooling = service.queryCooldown("st", "v1");
        assertEquals(BASE.toEpochMilli(), cooling.lastConfirmedAtUtc());
        assertEquals(BASE.toEpochMilli() + 10 * 60_000L, cooling.cooldownUntilUtc());
        assertTrue(cooling.cooling());

        mutableClock().advanceMillis(10 * 60_000L);
        CooldownStatusResponse elapsed = service.queryCooldown("st", "v1");
        assertTrue(!elapsed.cooling());
        assertEquals(BASE.toEpochMilli() + 10 * 60_000L, elapsed.cooldownUntilUtc());

        assert404(() -> service.queryCooldown("nope", "v1"));
        assert404(() -> service.queryDecay("nope", "v1", DAY));
    }

    @Test
    @DisplayName("HTTP 语义：冷却配置修改 200/409/400，冷却 429 携带 cooldownUntilUtc，状态与衰减查询 200")
    void httpSemantics_cooldownAndDecayEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"hc\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":10,\"cooldownMinutes\":10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cooldownMinutes").value(10))
                .andExpect(jsonPath("$.version").value(0));

        // 版本冲突 → 409
        mockMvc.perform(put("/api/exposure/campaigns/hc/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"expectedVersion\":5,\"cooldownMinutes\":20}"))
                .andExpect(status().isConflict());

        // 冷却分钟数越界 → 400
        mockMvc.perform(put("/api/exposure/campaigns/hc/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"expectedVersion\":0,\"cooldownMinutes\":2000}"))
                .andExpect(status().isBadRequest());

        // 正确版本 → 200，版本 +1
        mockMvc.perform(put("/api/exposure/campaigns/hc/cooldown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"expectedVersion\":0,\"cooldownMinutes\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownMinutes").value(20))
                .andExpect(jsonPath("$.version").value(1));

        // 申请并确认
        String reservationId = mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"campaignId\":\"hc\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"reservationId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/exposure/reservations/" + reservationId + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 冷却期内再申请 → 429 且响应体携带冷却结束 UTC 时刻
        long until = BASE.toEpochMilli() + 20 * 60_000L;
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h7\",\"campaignId\":\"hc\",\"visitorId\":\"u1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.cooldownUntilUtc").value(until));

        // 冷却状态查询
        mockMvc.perform(get("/api/exposure/campaigns/hc/cooldown")
                        .param("visitorId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooldownMinutes").value(20))
                .andExpect(jsonPath("$.lastConfirmedAtUtc").value(BASE.toEpochMilli()))
                .andExpect(jsonPath("$.cooldownUntilUtc").value(until))
                .andExpect(jsonPath("$.cooling").value(true));

        // 衰减权重明细查询
        mockMvc.perform(get("/api/exposure/campaigns/hc/decay")
                        .param("visitorId", "u1")
                        .param("utcDate", "2026-09-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sequenceNo").value(1))
                .andExpect(jsonPath("$[0].decayWeight").value(1.0))
                .andExpect(jsonPath("$[0].reservationId").value(reservationId));
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
