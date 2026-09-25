package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.exposure.SuppressionService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateSuppressionRequest;
import com.example.starter.exposure.web.ExposureDecisionResponse;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.SuppressionAddSpec;
import com.example.starter.exposure.web.SuppressionBatchUpdateRequest;
import com.example.starter.exposure.web.SuppressionBatchUpdateResponse;
import com.example.starter.exposure.web.SuppressionHistoryResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionStatusResponse;
import com.example.starter.exposure.web.SuppressionTerminateAction;
import com.example.starter.exposure.web.SuppressionTerminateSpec;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 访客抑制名单与频控账目联合裁决 H2（MODE=MySQL）集成测试：
 * 半开时间区间、名单批量原子性、账目不倒改、频控隔离与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuppressionIntegrationTest {

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

    static final Instant BASE = Instant.parse("2026-09-26T10:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 26);
    static final long T0 = BASE.toEpochMilli();

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        Clock mutableClock() {
            return new MutableClock(BASE);
        }
    }

    @Autowired
    ExposureService exposureService;
    @Autowired
    SuppressionService suppressionService;
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
        jdbc.update("DELETE FROM suppression_delete_record");
        jdbc.update("DELETE FROM suppression_interval");
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

    private void createCampaign(String requestId, String campaignId, int total, int perVisitor) {
        exposureService.createCampaign(
                new CreateCampaignRequest(requestId, campaignId, total, perVisitor));
    }

    private SuppressionIntervalResponse addInterval(
            String requestId, String campaignId, String visitorId, long start, long end) {
        return suppressionService.createInterval(campaignId,
                new CreateSuppressionRequest(requestId, visitorId, start, end));
    }

    private ExposureDecisionResponse decide(String requestId, String campaignId, String visitorId,
                                            String placement, Long atUtc) {
        return exposureService.decide(
                new ApplyExposureRequest(requestId, campaignId, visitorId, placement, atUtc));
    }

    // ---------- 名单创建与半开时间区间 ----------

    @Test
    @DisplayName("创建抑制区间成功并推进公告版本；起止非法 422")
    void createInterval_validAdvancesVersion_invalidRange422() {
        createCampaign("req-c", "c1", 10, 10);

        SuppressionIntervalResponse interval = addInterval("req-s1", "c1", "v1", T0, T0 + 1000);
        assertEquals("c1", interval.campaignId());
        assertEquals("v1", interval.visitorId());
        assertEquals(T0, interval.startAtUtc());
        assertEquals(T0 + 1000, interval.endAtUtc());
        assertEquals(T0 + 1000, interval.originalEndAtUtc());
        assertEquals("ACTIVE", interval.status().name());
        assertNull(interval.deletedAtUtc());

        // 版本推进一次
        assertEquals(1, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'c1'",
                Integer.class).intValue());

        // start == end 非法
        assert422(() -> addInterval("req-s2", "c1", "v2", T0, T0));
        // start > end 非法
        assert422(() -> addInterval("req-s3", "c1", "v2", T0 + 100, T0));
        // 失败不占键、不推进版本
        assertEquals(1, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'c1'",
                Integer.class).intValue());
    }

    @Test
    @DisplayName("同一访客重叠区间 409；相邻半开区间与不同访客区间合法")
    void overlap409_adjacentAndOtherVisitorAllowed() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s1", "c1", "v1", T0, T0 + 1000);

        // 与已有区间重叠
        assert409(() -> addInterval("req-s2", "c1", "v1", T0 + 500, T0 + 1500));
        assert409(() -> addInterval("req-s3", "c1", "v1", T0 - 500, T0 + 1));
        // 首尾相邻（一端右开 == 另一端左闭）不算重叠
        SuppressionIntervalResponse adjacent = addInterval("req-s4", "c1", "v1",
                T0 + 1000, T0 + 2000);
        assertNotNull(adjacent.intervalId());
        // 不同访客的相同时段不冲突
        assertNotNull(addInterval("req-s5", "c1", "v2", T0, T0 + 1000).intervalId());
    }

    // ---------- 联合裁决：SUPPRESSED 不扣账不建预占 ----------

    @Test
    @DisplayName("命中抑制：任何展示位返回 SUPPRESSED，不创建预占、不扣频次或预算")
    void suppressedAcrossPlacements_noReservationNoLedger() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s1", "c1", "v1", T0 - 1000, T0 + 1000);

        for (String placement : List.of("banner", "popup", "feed")) {
            ExposureDecisionResponse decision = decide("req-apply-" + placement,
                    "c1", "v1", placement, null);
            assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED, decision.outcome());
            assertNull(decision.reservation());
            assertNotNull(decision.suppressionReason());
            assertEquals("v1", decision.suppressionReason().visitorId());
        }

        Integer reservations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation WHERE campaign_id = 'c1'", Integer.class);
        assertEquals(0, reservations, "被抑制不得创建预占");
        QuotaResponse quota = exposureService.queryQuota("c1", null, DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(10, quota.remainingTotal());
        assertEquals(0, exposureService.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("半开边界：start 时刻被抑制，end 时刻可正常申请")
    void halfOpenBoundaries_startSuppressed_endAllowed() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s1", "c1", "v1", T0, T0 + 1000);

        ExposureDecisionResponse atStart = decide("req-a1", "c1", "v1", "p", T0);
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED, atStart.outcome());

        ExposureDecisionResponse atEnd = decide("req-a2", "c1", "v1", "p", T0 + 1000);
        assertEquals(ExposureDecisionResponse.OUTCOME_RESERVED, atEnd.outcome());
        assertNotNull(atEnd.reservation());
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal());

        // 区间之前也不受抑制
        ExposureDecisionResponse before = decide("req-a3", "c1", "v1", "p", T0 - 1);
        assertEquals(ExposureDecisionResponse.OUTCOME_RESERVED, before.outcome());
    }

    @Test
    @DisplayName("抑制优先于频控：额度已满时被抑制访客仍得 SUPPRESSED 而非 429，且不扣账")
    void suppressionPrecedesQuota() {
        createCampaign("req-c", "c1", 1, 100);
        // 耗尽公告总额度
        ExposureDecisionResponse first = decide("req-a1", "c1", "v2", "p", null);
        assertEquals(ExposureDecisionResponse.OUTCOME_RESERVED, first.outcome());

        addInterval("req-s1", "c1", "v1", T0 - 1000, T0 + 1000);
        ExposureDecisionResponse decision = decide("req-a2", "c1", "v1", "p", null);
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED, decision.outcome());
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(0, exposureService.queryQuota("c1", "v1", DAY).usedVisitor());
    }

    @Test
    @DisplayName("频控按访客隔离：v1 被抑制不扣账，v2 申请与额度不受影响")
    void visitorIsolation() {
        createCampaign("req-c", "c1", 10, 1);
        addInterval("req-s1", "c1", "v1", T0 - 1000, T0 + 1000);

        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED,
                decide("req-a1", "c1", "v1", "p", null).outcome());
        assertEquals(ExposureDecisionResponse.OUTCOME_RESERVED,
                decide("req-a2", "c1", "v2", "p", null).outcome());
        // v2 访客上限为 1：第二次 429，证明账按访客独立且未受 v1 抑制影响
        assert429(() -> decide("req-a3", "c1", "v2", "p", null));
        assertEquals(0, exposureService.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(1, exposureService.queryQuota("c1", "v2", DAY).usedVisitor());
    }

    @Test
    @DisplayName("预占创建后新增抑制不回滚已存在预占；回执仍按既有规则结算")
    void suppressionAfterReservation_doesNotRollback() {
        createCampaign("req-c", "c1", 10, 10);
        ExposureDecisionResponse decision = decide("req-a1", "c1", "v1", "p", null);
        String reservationId = decision.reservation().reservationId();
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal());

        // 预占已创建后新增抑制
        addInterval("req-s1", "c1", "v1", T0, T0 + 100_000);

        // 已存在预占仍可确认，账目不倒改
        var confirmed = exposureService.confirm(reservationId,
                new com.example.starter.exposure.web.ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal());
        assertEquals(1, exposureService.queryQuota("c1", "v1", DAY).usedVisitor());

        // 新的展示申请按最新名单判定为 SUPPRESSED
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED,
                decide("req-a2", "c1", "v1", "p2", null).outcome());
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal(),
                "被抑制的新申请不扣账");
    }

    // ---------- 批量更新原子性 ----------

    @Test
    @DisplayName("批量更新：版本不匹配 409；整批不变")
    void batchUpdate_versionMismatch409() {
        createCampaign("req-c", "c1", 10, 10);
        var request = new SuppressionBatchUpdateRequest("req-b1", 99,
                List.of(new SuppressionAddSpec("v1", T0, T0 + 1000)), List.of());
        assert409(() -> suppressionService.batchUpdate("c1", request));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_interval WHERE campaign_id = 'c1'", Integer.class));
    }

    @Test
    @DisplayName("批量更新：新增区间彼此重叠或起止非法时整批 422，原名单与版本不变")
    void batchUpdate_invalidWholeBatch422_nothingChanges() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s0", "c1", "v1", T0, T0 + 1000);

        // 本批两个新增区间重叠
        var overlapping = new SuppressionBatchUpdateRequest("req-b1", 1,
                List.of(new SuppressionAddSpec("v9", T0 + 5000, T0 + 7000),
                        new SuppressionAddSpec("v9", T0 + 6000, T0 + 8000)),
                List.of());
        assert422(() -> suppressionService.batchUpdate("c1", overlapping));

        // 新增与既有区间重叠
        var withExisting = new SuppressionBatchUpdateRequest("req-b2", 1,
                List.of(new SuppressionAddSpec("v1", T0 + 500, T0 + 1500)), List.of());
        assert422(() -> suppressionService.batchUpdate("c1", withExisting));

        // 起止非法
        var illegal = new SuppressionBatchUpdateRequest("req-b3", 1,
                List.of(new SuppressionAddSpec("v9", T0 + 5000, T0 + 5000)), List.of());
        assert422(() -> suppressionService.batchUpdate("c1", illegal));

        // 原名单不变、版本不变、失败不占键
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM suppression_interval WHERE campaign_id = 'c1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'c1'",
                Integer.class).intValue());
    }

    @Test
    @DisplayName("批量删除未开始区间：立即失效并保留不可变删除记录；删除已开始区间 422")
    void batchDelete_futureInvalidatesWithAudit_startedRejected() {
        createCampaign("req-c", "c1", 10, 10);
        // future 未开始；started 已开始未结束
        SuppressionIntervalResponse future = addInterval("req-s1", "c1", "v1",
                T0 + 10_000, T0 + 20_000);
        SuppressionIntervalResponse started = addInterval("req-s2", "c1", "v2",
                T0 - 10_000, T0 + 10_000);

        // 删除已开始区间必须 422
        var deleteStarted = new SuppressionBatchUpdateRequest("req-bad", 2, List.of(),
                List.of(new SuppressionTerminateSpec(started.intervalId(),
                        SuppressionTerminateAction.DELETE, null)));
        assert422(() -> suppressionService.batchUpdate("c1", deleteStarted));
        // 恰在 start 时刻也算已开始
        SuppressionIntervalResponse boundary = addInterval("req-s3", "c1", "v3", T0, T0 + 5000);
        var deleteBoundary = new SuppressionBatchUpdateRequest("req-bad2", 3, List.of(),
                List.of(new SuppressionTerminateSpec(boundary.intervalId(),
                        SuppressionTerminateAction.DELETE, null)));
        assert422(() -> suppressionService.batchUpdate("c1", deleteBoundary));

        // 合法删除未开始区间
        var deleteFuture = new SuppressionBatchUpdateRequest("req-b1", 3, List.of(),
                List.of(new SuppressionTerminateSpec(future.intervalId(),
                        SuppressionTerminateAction.DELETE, null)));
        SuppressionBatchUpdateResponse resp = suppressionService.batchUpdate("c1", deleteFuture);
        assertEquals(4, resp.version());

        // 区间行变为 DELETED 快照，且原时间窗口不再抑制
        SuppressionHistoryResponse history = suppressionService.queryHistory("c1", "v1");
        SuppressionIntervalResponse deleted = history.intervals().get(0);
        assertEquals("DELETED", deleted.status().name());
        assertEquals(T0, deleted.deletedAtUtc());
        assertEquals(T0 + 10_000, deleted.startAtUtc(), "快照保留原计划开始时刻");
        assertEquals(T0 + 20_000, deleted.endAtUtc(), "快照保留原计划结束时刻");
        // 不可变删除记录存在
        assertEquals(1, history.deleteRecords().size());
        assertEquals(future.intervalId(), history.deleteRecords().get(0).intervalId());
        assertEquals("req-b1", history.deleteRecords().get(0).requestId());
        // 删除后状态查询为未抑制
        SuppressionStatusResponse status =
                suppressionService.queryStatus("c1", "v1", T0 + 15_000);
        assertFalse(status.suppressed());
    }

    @Test
    @DisplayName("批量提前结束已开始区间：新结束时刻不得早于当前时刻，提前结束后窗口外不再抑制")
    void batchEndEarly_rulesAndEffect() {
        createCampaign("req-c", "c1", 10, 10);
        SuppressionIntervalResponse interval = addInterval("req-s1", "c1", "v1",
                T0 - 10_000, T0 + 20_000);

        // 未开始区间不能 END_EARLY
        SuppressionIntervalResponse future = addInterval("req-s2", "c1", "v2",
                T0 + 10_000, T0 + 30_000);
        assert422(() -> suppressionService.batchUpdate("c1",
                new SuppressionBatchUpdateRequest("req-bad1", 2, List.of(),
                        List.of(new SuppressionTerminateSpec(future.intervalId(),
                                SuppressionTerminateAction.END_EARLY, T0 + 15_000)))));
        // 新结束时刻早于当前时刻
        assert422(() -> suppressionService.batchUpdate("c1",
                new SuppressionBatchUpdateRequest("req-bad2", 2, List.of(),
                        List.of(new SuppressionTerminateSpec(interval.intervalId(),
                                SuppressionTerminateAction.END_EARLY, T0 - 1)))));
        // 新结束时刻不早于当前结束时刻（没有缩短）
        assert422(() -> suppressionService.batchUpdate("c1",
                new SuppressionBatchUpdateRequest("req-bad3", 2, List.of(),
                        List.of(new SuppressionTerminateSpec(interval.intervalId(),
                                SuppressionTerminateAction.END_EARLY, T0 + 20_000)))));

        // 合法提前结束到当前时刻
        suppressionService.batchUpdate("c1",
                new SuppressionBatchUpdateRequest("req-b1", 2, List.of(),
                        List.of(new SuppressionTerminateSpec(interval.intervalId(),
                                SuppressionTerminateAction.END_EARLY, T0))));
        SuppressionHistoryResponse history = suppressionService.queryHistory("c1", "v1");
        SuppressionIntervalResponse shortened = history.intervals().get(0);
        assertEquals(T0, shortened.endAtUtc());
        assertEquals(T0 + 20_000, shortened.originalEndAtUtc(), "originalEnd 不可变");
        assertEquals(T0, shortened.endedEarlyAtUtc());
        // T0 起不再抑制（半开）
        assertFalse(suppressionService.queryStatus("c1", "v1", T0).suppressed());
        assertTrue(suppressionService.queryStatus("c1", "v1", T0 - 1).suppressed());
        // 不能重复提前结束（已经在 T0 自然结束）
        assert422(() -> suppressionService.batchUpdate("c1",
                new SuppressionBatchUpdateRequest("req-b2", 3, List.of(),
                        List.of(new SuppressionTerminateSpec(interval.intervalId(),
                                SuppressionTerminateAction.END_EARLY, T0)))));
    }

    @Test
    @DisplayName("批量更新成功：删除/提前结束/新增混合，最终集合无重叠，版本+1")
    void batchUpdate_mixedSuccess_finalSetValidated() {
        createCampaign("req-c", "c1", 10, 10);
        SuppressionIntervalResponse a = addInterval("req-s1", "c1", "v1", T0, T0 + 10_000);
        addInterval("req-s2", "c1", "v1", T0 + 20_000, T0 + 30_000);

        // a 提前结束到 T0+5000；新增与缩短后的 a 相邻的区间 [T0+5000,T0+8000) 合法；
        // 若不考虑提前结束，该新增会与原 a 重叠——证明校验基于最终集合
        var request = new SuppressionBatchUpdateRequest("req-b1", 2,
                List.of(new SuppressionAddSpec("v1", T0 + 5_000, T0 + 8_000)),
                List.of(new SuppressionTerminateSpec(a.intervalId(),
                        SuppressionTerminateAction.END_EARLY, T0 + 5_000)));
        SuppressionBatchUpdateResponse resp = suppressionService.batchUpdate("c1", request);
        assertEquals(3, resp.version());
        List<SuppressionIntervalResponse> active = resp.activeIntervals();
        assertEquals(3, active.size());

        // 同一最终集合中若新增区间与缩短后的 a 重叠则整批失败
        var overlapping = new SuppressionBatchUpdateRequest("req-b2", 3,
                List.of(new SuppressionAddSpec("v1", T0 + 4_000, T0 + 6_000)), List.of());
        assert422(() -> suppressionService.batchUpdate("c1", overlapping));
        assertEquals(3, jdbc.queryForObject("SELECT version FROM campaign WHERE campaign_id = 'c1'",
                Integer.class).intValue());
    }

    @Test
    @DisplayName("批量更新幂等：同键同参重放原结果；异参 409；失败不占键")
    void batchUpdate_idempotency() {
        createCampaign("req-c", "c1", 10, 10);
        var request = new SuppressionBatchUpdateRequest("key-1", 0,
                List.of(new SuppressionAddSpec("v1", T0, T0 + 1000)), List.of());
        SuppressionBatchUpdateResponse first = suppressionService.batchUpdate("c1", request);
        assertEquals(1, first.version());

        // 同键同参重放：版本号仍是首调结果 1（而非再次推进到 2）
        SuppressionBatchUpdateResponse replay = suppressionService.batchUpdate("c1", request);
        assertEquals(1, replay.version());

        // 同键异参 409
        var different = new SuppressionBatchUpdateRequest("key-1", 1,
                List.of(new SuppressionAddSpec("v2", T0, T0 + 1000)), List.of());
        assert409(() -> suppressionService.batchUpdate("c1", different));
    }

    // ---------- 曝光申请幂等指纹 ----------

    @Test
    @DisplayName("曝光幂等：同键同参重放首个完整响应；异参（展示位/时刻/版本）409；失败不占键")
    void applyIdempotency_fingerprintIncludesPlacementTimeVersion() {
        createCampaign("req-c", "c1", 10, 10);
        ExposureDecisionResponse first = decide("key-1", "c1", "v1", "banner", T0);
        assertEquals(first.reservation().reservationId(),
                decide("key-1", "c1", "v1", "banner", T0).reservation().reservationId());
        assertEquals(1, exposureService.queryQuota("c1", null, DAY).usedTotal());

        // 不同展示位 → 异参 409（展示位参与指纹）
        assert409(() -> decide("key-1", "c1", "v1", "popup", T0));
        // 不同请求时刻 → 异参 409
        assert409(() -> decide("key-1", "c1", "v1", "banner", T0 + 1));
        // 不同访客 → 异参 409
        assert409(() -> decide("key-1", "c1", "v9", "banner", T0));

        // 名单变更推进版本后，同键重放因公告版本变化 → 409
        addInterval("req-s1", "c1", "v1", T0 + 100_000, T0 + 200_000);
        assert409(() -> decide("key-1", "c1", "v1", "banner", T0));

        // 失败不占键：满额 429 的键在额度释放后可成功
        createCampaign("req-c2", "c2", 1, 10);
        decide("occupy", "c2", "vx", "banner", null);
        assert429(() -> decide("key-fail", "c2", "vy", "banner", null));
        exposureService.cancel(
                jdbc.queryForObject("SELECT reservation_id FROM exposure_reservation "
                                + "WHERE campaign_id = 'c2'", String.class),
                new com.example.starter.exposure.web.ReservationActionRequest("req-x1"));
        assertEquals(ExposureDecisionResponse.OUTCOME_RESERVED,
                decide("key-fail", "c2", "vy", "banner", null).outcome());
    }

    @Test
    @DisplayName("SUPPRESSED 是完整成功响应并占幂等键：同键重放仍为 SUPPRESSED，异参 409")
    void suppressedResponse_isIdempotentSuccess() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s1", "c1", "v1", T0, T0 + 10_000);

        ExposureDecisionResponse first = decide("key-s", "c1", "v1", "banner", null);
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED, first.outcome());
        ExposureDecisionResponse replay = decide("key-s", "c1", "v1", "banner", null);
        assertEquals(ExposureDecisionResponse.OUTCOME_SUPPRESSED, replay.outcome());
        assertEquals(first.suppressionReason().intervalId(),
                replay.suppressionReason().intervalId());
        assert409(() -> decide("key-s", "c1", "v1", "popup", null));
        assertEquals(0, exposureService.queryQuota("c1", null, DAY).usedTotal());
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("查询抑制状态、区间历史和被抑制原因")
    void queryStatusHistoryAndReason() {
        createCampaign("req-c", "c1", 10, 10);
        addInterval("req-s1", "c1", "v1", T0, T0 + 10_000);

        SuppressionStatusResponse hit = suppressionService.queryStatus("c1", "v1", T0 + 100);
        assertTrue(hit.suppressed());
        assertNotNull(hit.reason());
        assertTrue(hit.reason().reason().contains("v1"));

        SuppressionStatusResponse miss = suppressionService.queryStatus("c1", "v1", T0 + 10_000);
        assertFalse(miss.suppressed());
        assertNull(miss.reason());

        SuppressionStatusResponse defaultTime = suppressionService.queryStatus("c1", "v1", null);
        assertTrue(defaultTime.suppressed(), "atUtc 缺省取服务端当前时刻");

        SuppressionHistoryResponse history = suppressionService.queryHistory("c1", "v1");
        assertEquals(1, history.intervals().size());
        assertEquals(0, history.deleteRecords().size());

        // 不存在公告 404
        assert404(() -> suppressionService.queryStatus("nope", "v1", null));
    }

    // ---------- HTTP 语义 ----------

    @Test
    @DisplayName("HTTP：联合裁决 201/200，名单重叠 409、非法 422，状态查询 200")
    void httpSemantics() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h1\",\"campaignId\":\"ch\",\"dailyTotalCap\":2,"
                                + "\"perVisitorDailyCap\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));

        // 正常裁决 201
        mockMvc.perform(post("/api/exposure/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h2\",\"campaignId\":\"ch\",\"visitorId\":\"u1\","
                                + "\"placementId\":\"banner\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("RESERVED"))
                .andExpect(jsonPath("$.reservation.placementId").value("banner"));

        // 创建覆盖当前时刻的抑制区间
        long now = T0;
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h3\",\"visitorId\":\"u2\","
                                + "\"startAtUtc\":" + (now - 1000) + ",\"endAtUtc\":" + (now + 1000) + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 被抑制 200 + SUPPRESSED
        mockMvc.perform(post("/api/exposure/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h4\",\"campaignId\":\"ch\",\"visitorId\":\"u2\","
                                + "\"placementId\":\"feed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("SUPPRESSED"))
                .andExpect(jsonPath("$.reservation").doesNotExist())
                .andExpect(jsonPath("$.suppressionReason.intervalId").isNotEmpty());

        // 状态查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/suppressions/status")
                        .param("visitorId", "u2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressed").value(true));

        // 起止非法 422
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppressions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h5\",\"visitorId\":\"u3\","
                                + "\"startAtUtc\":100,\"endAtUtc\":100}"))
                .andExpect(status().isUnprocessableEntity());

        // 批量版本不匹配 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppressions/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h6\",\"expectedVersion\":99,"
                                + "\"addIntervals\":[],\"terminateIntervals\":[]}"))
                .andExpect(status().isConflict());
    }

    private void assert409(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(409, ex.getStatus().value());
    }

    private void assert422(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(422, ex.getStatus().value());
    }

    private void assert429(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(429, ex.getStatus().value());
    }

    private void assert404(Runnable action) {
        ApiException ex = assertThrows(ApiException.class, action::run);
        assertEquals(404, ex.getStatus().value());
    }
}
