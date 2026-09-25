package com.example.starter.exposure;

import com.example.starter.exposure.exposure.ExposureService;
import com.example.starter.exposure.web.ApiException;
import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.BatchUpdateSuppressionListRequest;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.CreateSuppressionIntervalRequest;
import com.example.starter.exposure.web.EndSuppressionIntervalRequest;
import com.example.starter.exposure.web.IdempotentRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SuppressedResponse;
import com.example.starter.exposure.web.SuppressionIntervalResponse;
import com.example.starter.exposure.web.SuppressionListResponse;
import com.example.starter.exposure.web.VisitorSuppressionStatusResponse;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * 访客抑制名单 H2（MODE=MySQL）测试：时间区间左闭右开、批量原子性、
 * 删除/提前结束规则、账目不倒改、频控隔离、并发裁决与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuppressionListTest {

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
    static final long HOUR = 3_600_000L;

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
        jdbc.update("DELETE FROM suppression_interval");
        jdbc.update("DELETE FROM campaign");
        mutableClock().setInstant(BASE);
    }

    @AfterAll
    static void releaseDatabase(@Autowired JdbcTemplate jdbc) {
        jdbc.execute("SHUTDOWN");
    }

    private void createCampaign(String campaignId, int total, int perVisitor) {
        service.createCampaign(new CreateCampaignRequest("req-c-" + campaignId, campaignId, total, perVisitor));
    }

    private ApplyExposureRequest applyReq(String requestId, String campaignId, String visitorId) {
        return new ApplyExposureRequest(requestId, campaignId, visitorId, "slot-1", BASE.toEpochMilli());
    }

    private CreateSuppressionIntervalRequest intervalReq(String requestId, String visitorId,
                                                         long from, long until) {
        return new CreateSuppressionIntervalRequest(requestId, visitorId, from, until);
    }

    private void assertStatus(Runnable action, int expectedStatus) {
        var ex = assertThrows(ApiException.class, action::run);
        assertEquals(expectedStatus, ex.getStatus().value());
    }

    @Test
    @DisplayName("命中抑制区间：返回 SUPPRESSED，不创建预占、不扣额度，状态与原因可查")
    void suppressedApply_noReservationNoQuota_statusAndReasonQueryable() {
        createCampaign("c1", 10, 2);
        SuppressionIntervalResponse interval = service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));
        assertEquals("ACTIVE", interval.status().name());
        assertEquals(BASE.toEpochMilli(), interval.validFromUtc());

        SuppressedResponse suppressed = (SuppressedResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("SUPPRESSED", suppressed.status());
        assertEquals("SUPPRESSED_BY_INTERVAL", suppressed.reason());
        assertEquals(interval.intervalId(), suppressed.intervalId());
        assertEquals("slot-1", suppressed.placementId());
        assertEquals(BASE.toEpochMilli(), suppressed.decidedAtUtc());

        // 不创建预占、不扣频次或预算
        Integer reservationCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exposure_reservation", Integer.class);
        assertEquals(0, reservationCount);
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(0, quota.usedTotal());
        assertEquals(0, quota.usedVisitor());

        // 查询访客抑制状态与被抑制原因
        VisitorSuppressionStatusResponse status = service.getVisitorSuppressionStatus("c1", "v1");
        assertTrue(status.suppressed());
        assertEquals("SUPPRESSED_BY_INTERVAL", status.reason());
        assertEquals(interval.intervalId(), status.matchedInterval().intervalId());
        assertEquals(BASE.toEpochMilli(), status.checkedAtUtc());

        VisitorSuppressionStatusResponse other = service.getVisitorSuppressionStatus("c1", "v2");
        assertFalse(other.suppressed());
        assertNull(other.reason());
        assertNull(other.matchedInterval());

        // 区间历史可查
        SuppressionListResponse history = service.listSuppressionIntervals("c1", "v1");
        assertEquals(1, history.intervals().size());
        assertEquals(interval.intervalId(), history.intervals().get(0).intervalId());
        assertEquals(1L, history.version(), "名单变更后活动版本 +1");
    }

    @Test
    @DisplayName("生效区间左闭右开：起始时刻命中，结束时刻不再命中")
    void intervalBoundaries_leftClosedRightOpen() {
        createCampaign("c1", 10, 10);
        service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli() + 60_000L, BASE.toEpochMilli() + 120_000L));

        // 起始前：不命中
        ReservationResponse before = (ReservationResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("RESERVED", before.status().name());

        // 恰好起始时刻：命中
        mutableClock().setInstant(BASE.plusMillis(60_000L));
        SuppressedResponse atStart = (SuppressedResponse) service.apply(applyReq("req-a2", "c1", "v1"));
        assertEquals("SUPPRESSED", atStart.status());

        // 区间内：命中
        mutableClock().setInstant(BASE.plusMillis(119_999L));
        SuppressedResponse inside = (SuppressedResponse) service.apply(applyReq("req-a3", "c1", "v1"));
        assertEquals("SUPPRESSED", inside.status());

        // 恰好结束时刻：不再命中（右开）
        mutableClock().setInstant(BASE.plusMillis(120_000L));
        ReservationResponse atEnd = (ReservationResponse) service.apply(applyReq("req-a4", "c1", "v1"));
        assertEquals("RESERVED", atEnd.status().name());
    }

    @Test
    @DisplayName("单条创建：起止非法 422，同访客重叠 409，相邻区间与不同访客不受限")
    void createInterval_invalidRange422_overlap409() {
        createCampaign("c1", 10, 2);

        // 起止非法：from >= until → 422
        assertStatus(() -> service.createSuppressionInterval("c1",
                intervalReq("req-bad1", "v1", BASE.toEpochMilli() + 1000L, BASE.toEpochMilli() + 1000L)), 422);
        assertStatus(() -> service.createSuppressionInterval("c1",
                intervalReq("req-bad2", "v1", BASE.toEpochMilli() + 2000L, BASE.toEpochMilli() + 1000L)), 422);

        service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L));

        // 同访客重叠 → 409
        assertStatus(() -> service.createSuppressionInterval("c1",
                intervalReq("req-i2", "v1", BASE.toEpochMilli() + 500L, BASE.toEpochMilli() + 1500L)), 409);
        assertStatus(() -> service.createSuppressionInterval("c1",
                intervalReq("req-i3", "v1", BASE.toEpochMilli() - 500L, BASE.toEpochMilli() + 500L)), 409);

        // 左闭右开相邻（from == 现有 until）不算重叠 → 成功
        SuppressionIntervalResponse adjacent = service.createSuppressionInterval("c1",
                intervalReq("req-i4", "v1", BASE.toEpochMilli() + 1000L, BASE.toEpochMilli() + 2000L));
        assertEquals("ACTIVE", adjacent.status().name());

        // 不同访客同区间不受限 → 成功
        service.createSuppressionInterval("c1",
                intervalReq("req-i5", "v2", BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L));

        // 公告不存在 → 404
        assertStatus(() -> service.createSuppressionInterval("nope",
                intervalReq("req-i6", "v1", 0L, 1000L)), 404);

        SuppressionListResponse list = service.listSuppressionIntervals("c1", null);
        assertEquals(3, list.intervals().size());
        assertEquals(3L, list.version(), "每次成功创建版本 +1");
    }

    @Test
    @DisplayName("批量更新：版本不一致 409，任一重叠或起止非法整批 422 且原名单不变，失败不占键")
    void batchUpdate_atomic_versionChecked_failureDoesNotOccupyKey() {
        createCampaign("c1", 10, 2);
        long from = BASE.toEpochMilli();

        // 版本不一致 → 409，名单不变
        assertStatus(() -> service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b0", 5L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v1", from, from + 1000L)))), 409);
        assertEquals(0, service.listSuppressionIntervals("c1", null).intervals().size());
        assertEquals(0L, service.listSuppressionIntervals("c1", null).version());

        // 批内含起止非法项 → 整批 422，名单与版本不变
        assertStatus(() -> service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b1", 0L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v1", from, from + 1000L),
                                new BatchUpdateSuppressionListRequest.Item("v2", from + 500L, from + 500L)))),
                422);
        assertEquals(0, service.listSuppressionIntervals("c1", null).intervals().size());
        assertEquals(0L, service.listSuppressionIntervals("c1", null).version());

        // 失败不占键：修正后同 requestId 可成功
        SuppressionListResponse fixed = service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b1", 0L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v1", from, from + 1000L),
                                new BatchUpdateSuppressionListRequest.Item("v2", from, from + 1000L))));
        assertEquals(2, fixed.intervals().size());
        assertEquals(1L, fixed.version());

        // 与现有生效区间重叠 → 整批 422，原名单不变
        assertStatus(() -> service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b2", 1L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v1", from + 500L, from + 1500L)))),
                422);
        SuppressionListResponse after = service.listSuppressionIntervals("c1", null);
        assertEquals(2, after.intervals().size());
        assertEquals(1L, after.version());

        // 批内两条同访客区间互相重叠 → 422
        assertStatus(() -> service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b3", 1L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v3", from + 2000L, from + 3000L),
                                new BatchUpdateSuppressionListRequest.Item("v3", from + 2500L, from + 3500L)))),
                422);
        assertEquals(2, service.listSuppressionIntervals("c1", null).intervals().size());

        // 合法批量：版本前进，名单生效
        SuppressionListResponse ok = service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b4", 1L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v1", from + 1000L, from + 2000L))));
        assertEquals(2L, ok.version());
        assertEquals(3, ok.intervals().size());
        SuppressedResponse suppressed = (SuppressedResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("SUPPRESSED", suppressed.status());
    }

    @Test
    @DisplayName("删除未开始区间：立即失效并保留不可变删除记录；已开始的区间删除 409")
    void delete_notStarted_immediateEffect_immutableRecord() {
        createCampaign("c1", 10, 2);
        // 未开始区间
        SuppressionIntervalResponse future = service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli() + 60_000L, BASE.toEpochMilli() + 120_000L));

        SuppressionIntervalResponse deleted = service.deleteSuppressionInterval("c1",
                future.intervalId(), new IdempotentRequest("req-d1"));
        assertEquals("DELETED", deleted.status().name());
        assertEquals(BASE.toEpochMilli(), deleted.deletedAtUtc());

        // 立即失效：推进到原区间内，申请不再被抑制
        mutableClock().setInstant(BASE.plusMillis(90_000L));
        ReservationResponse applied = (ReservationResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("RESERVED", applied.status().name());

        // 删除记录不可变：重复删除/提前结束均 409
        assertStatus(() -> service.deleteSuppressionInterval("c1",
                future.intervalId(), new IdempotentRequest("req-d2")), 409);
        assertStatus(() -> service.endSuppressionInterval("c1",
                future.intervalId(), new EndSuppressionIntervalRequest("req-e1", BASE.toEpochMilli() + 100_000L)),
                409);

        // 删除记录保留在历史中
        SuppressionListResponse history = service.listSuppressionIntervals("c1", "v1");
        assertEquals(1, history.intervals().size());
        assertEquals("DELETED", history.intervals().get(0).status().name());
        assertNotNull(history.intervals().get(0).deletedAtUtc());

        // 已开始的区间不可删除 → 409
        mutableClock().setInstant(BASE);
        SuppressionIntervalResponse started = service.createSuppressionInterval("c1",
                intervalReq("req-i2", "v2", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));
        assertStatus(() -> service.deleteSuppressionInterval("c1",
                started.intervalId(), new IdempotentRequest("req-d3")), 409);
    }

    @Test
    @DisplayName("提前结束：仅已开始区间可结束；结束时刻不得早于当前时刻；结束后不再抑制")
    void endInterval_rules() {
        createCampaign("c1", 10, 2);
        SuppressionIntervalResponse interval = service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + 120_000L));

        // 结束时刻早于当前时刻 → 422
        assertStatus(() -> service.endSuppressionInterval("c1", interval.intervalId(),
                new EndSuppressionIntervalRequest("req-e1", BASE.toEpochMilli() - 1L)), 422);
        // 不早于当前生效结束时刻 → 422（不是提前结束）
        assertStatus(() -> service.endSuppressionInterval("c1", interval.intervalId(),
                new EndSuppressionIntervalRequest("req-e2", BASE.toEpochMilli() + 120_000L)), 422);

        // 合法提前结束到未来时刻：区间缩短但仍 ACTIVE，原始结束时刻留痕
        SuppressionIntervalResponse shortened = service.endSuppressionInterval("c1", interval.intervalId(),
                new EndSuppressionIntervalRequest("req-e3", BASE.toEpochMilli() + 60_000L));
        assertEquals("ACTIVE", shortened.status().name());
        assertEquals(BASE.toEpochMilli() + 60_000L, shortened.validUntilUtc());
        assertEquals(BASE.toEpochMilli() + 120_000L, shortened.originalValidUntilUtc());

        // 当前仍命中；推进到新结束时刻后不再抑制
        SuppressedResponse still = (SuppressedResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("SUPPRESSED", still.status());
        mutableClock().setInstant(BASE.plusMillis(60_000L));
        ReservationResponse after = (ReservationResponse) service.apply(applyReq("req-a2", "c1", "v1"));
        assertEquals("RESERVED", after.status().name());

        // 结束时刻等于当前时刻：立即转 EARLY_ENDED 且不可变
        SuppressionIntervalResponse second = service.createSuppressionInterval("c1",
                intervalReq("req-i2", "v2", BASE.toEpochMilli(), BASE.toEpochMilli() + 100_000L));
        SuppressionIntervalResponse ended = service.endSuppressionInterval("c1", second.intervalId(),
                new EndSuppressionIntervalRequest("req-e4", BASE.toEpochMilli() + 60_000L));
        assertEquals("EARLY_ENDED", ended.status().name());
        assertStatus(() -> service.endSuppressionInterval("c1", second.intervalId(),
                new EndSuppressionIntervalRequest("req-e5", BASE.toEpochMilli() + 70_000L)), 409);
        assertStatus(() -> service.deleteSuppressionInterval("c1", second.intervalId(),
                new IdempotentRequest("req-d1")), 409);

        // 未开始的区间不可提前结束 → 409
        SuppressionIntervalResponse future = service.createSuppressionInterval("c1",
                intervalReq("req-i3", "v3", BASE.toEpochMilli() + 300_000L, BASE.toEpochMilli() + 400_000L));
        assertStatus(() -> service.endSuppressionInterval("c1", future.intervalId(),
                new EndSuppressionIntervalRequest("req-e6", BASE.toEpochMilli() + 350_000L)), 409);
    }

    @Test
    @DisplayName("账目不倒改：已创建预占不因新增抑制回滚，回执照常结算，新申请按最新名单判定")
    void existingReservation_notRolledBack_newApplySuppressed() {
        createCampaign("c1", 10, 2);
        ReservationResponse reserved = (ReservationResponse) service.apply(applyReq("req-a1", "c1", "v1"));
        assertEquals("RESERVED", reserved.status().name());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedTotal());

        // 新增覆盖当前时刻的抑制区间
        service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));

        // 已存在预占不回滚：回执（确认）照常结算
        ReservationResponse confirmed = service.confirm(reserved.reservationId(),
                new ReservationActionRequest("req-k1"));
        assertEquals("CONFIRMED", confirmed.status().name());
        QuotaResponse quota = service.queryQuota("c1", "v1", DAY);
        assertEquals(1, quota.usedTotal(), "确认后额度保持占用，不倒改");
        assertEquals(1, quota.usedVisitor());

        // 新的展示申请按最新名单判定 → SUPPRESSED，额度不变
        SuppressedResponse suppressed = (SuppressedResponse) service.apply(applyReq("req-a2", "c1", "v1"));
        assertEquals("SUPPRESSED", suppressed.status());
        assertEquals(1, service.queryQuota("c1", "v1", DAY).usedTotal());
    }

    @Test
    @DisplayName("频控隔离：抑制判定先于额度，被抑制访客不扣额度，其他访客不受影响")
    void suppressionIsolation_perVisitor() {
        createCampaign("c1", 1, 1);
        service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));

        // 其他访客正常申请并占满总额度
        ReservationResponse other = (ReservationResponse) service.apply(applyReq("req-a1", "c1", "v2"));
        assertEquals("RESERVED", other.status().name());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());

        // 被抑制访客：即使额度已满也返回 SUPPRESSED（抑制优先于 429），且不扣额度
        SuppressedResponse suppressed = (SuppressedResponse) service.apply(applyReq("req-a2", "c1", "v1"));
        assertEquals("SUPPRESSED", suppressed.status());
        assertEquals(0, service.queryQuota("c1", "v1", DAY).usedVisitor());
        assertEquals(1, service.queryQuota("c1", null, DAY).usedTotal());
    }

    @Test
    @DisplayName("幂等：SUPPRESSED 同键同参重放首个响应，异参 409；名单写操作同键重放不重复生效")
    void idempotency_suppressedReplay_listWritesReplay() {
        createCampaign("c1", 10, 2);
        SuppressionIntervalResponse interval = service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));

        // 同键重放创建：返回原区间，版本只 +1 一次
        SuppressionIntervalResponse replayCreate = service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));
        assertEquals(interval.intervalId(), replayCreate.intervalId());
        assertEquals(1L, service.listSuppressionIntervals("c1", null).version());
        assertEquals(1, service.listSuppressionIntervals("c1", null).intervals().size());

        // 同键异参 → 409
        assertStatus(() -> service.createSuppressionInterval("c1",
                intervalReq("req-i1", "v1", BASE.toEpochMilli(), BASE.toEpochMilli() + 5000L)), 409);

        // SUPPRESSED 同键同参重放首个完整响应
        SuppressedResponse first = (SuppressedResponse) service.apply(applyReq("key-s", "c1", "v1"));
        SuppressedResponse replay = (SuppressedResponse) service.apply(applyReq("key-s", "c1", "v1"));
        assertEquals("SUPPRESSED", replay.status());
        assertEquals(first.intervalId(), replay.intervalId());
        assertEquals(first.decidedAtUtc(), replay.decidedAtUtc());

        // 同键异参（不同展示位）→ 409
        assertStatus(() -> service.apply(new ApplyExposureRequest("key-s", "c1", "v1", "slot-2",
                BASE.toEpochMilli())), 409);
        // 同键异参（不同请求时刻）→ 409
        assertStatus(() -> service.apply(new ApplyExposureRequest("key-s", "c1", "v1", "slot-1",
                BASE.toEpochMilli() + 1L)), 409);

        // 批量更新同键重放：不重复插入、版本不重复前进
        SuppressionListResponse batch = service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b1", 1L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v2",
                                BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L))));
        SuppressionListResponse batchReplay = service.batchUpdateSuppressionList("c1",
                new BatchUpdateSuppressionListRequest("req-b1", 1L,
                        List.of(new BatchUpdateSuppressionListRequest.Item("v2",
                                BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L))));
        assertEquals(batch.version(), batchReplay.version());
        assertEquals(batch.intervals().size(), batchReplay.intervals().size());
        assertEquals(2, service.listSuppressionIntervals("c1", null).intervals().size());
        assertEquals(2L, service.listSuppressionIntervals("c1", null).version());
    }

    @Test
    @DisplayName("并发创建同访客重叠区间：按事务提交顺序裁决，恰好一个成功其余 409")
    void concurrentOverlappingCreates_exactlyOneWins() throws Exception {
        createCampaign("c1", 10, 2);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.createSuppressionInterval("c1", intervalReq("req-i-" + idx, "v1",
                            BASE.toEpochMilli(), BASE.toEpochMilli() + HOUR));
                    success.incrementAndGet();
                } catch (ApiException ex) {
                    if (ex.getStatus().value() == 409) {
                        conflicts.incrementAndGet();
                    } else {
                        throw ex;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(1, success.get(), "重叠区间恰好一个创建成功");
        assertEquals(threads - 1, conflicts.get(), "其余必须 409");
        assertEquals(1, service.listSuppressionIntervals("c1", null).intervals().size());
        assertEquals(1L, service.listSuppressionIntervals("c1", null).version());
    }

    @Test
    @DisplayName("并发同键批量更新：只执行一次，响应一致，版本只前进一次")
    void concurrentSameKeyBatchUpdate_executesOnce() throws Exception {
        createCampaign("c1", 10, 2);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<SuppressionListResponse> distinct = ConcurrentHashMap.newKeySet();
        List<SuppressionListResponse> responses =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Throwable> firstError =
                new java.util.concurrent.atomic.AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    SuppressionListResponse response = service.batchUpdateSuppressionList("c1",
                            new BatchUpdateSuppressionListRequest("batch-key", 0L,
                                    List.of(new BatchUpdateSuppressionListRequest.Item("v1",
                                                    BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L),
                                            new BatchUpdateSuppressionListRequest.Item("v2",
                                                    BASE.toEpochMilli(), BASE.toEpochMilli() + 1000L))));
                    responses.add(response);
                    distinct.add(response);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    firstError.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时前完成");

        assertEquals(0, errors.get(), "并发同键重放不应报错: " + firstError.get());
        assertEquals(threads, responses.size());
        assertEquals(1, distinct.size(), "所有并发调用重放同一首个响应");
        SuppressionListResponse any = responses.get(0);
        assertEquals(1L, any.version(), "版本只前进一次");
        assertEquals(2, any.intervals().size(), "区间只插入一次");
        assertEquals(2, service.listSuppressionIntervals("c1", null).intervals().size());
        assertEquals(1L, service.listSuppressionIntervals("c1", null).version());
    }

    @Test
    @DisplayName("HTTP 语义：创建区间 201、重叠 409、非法 422、批量版本冲突 409、抑制申请 200 SUPPRESSED")
    void httpSemantics_suppressionEndpoints() throws Exception {
        mockMvc.perform(post("/api/exposure/campaigns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-c1\",\"campaignId\":\"ch\",\"dailyTotalCap\":10,"
                                + "\"perVisitorDailyCap\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(0));

        long from = BASE.toEpochMilli();
        long until = from + HOUR;
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppression-intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-i1\",\"visitorId\":\"u1\",\"validFromUtc\":" + from
                                + ",\"validUntilUtc\":" + until + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.visitorId").value("u1"));

        // 同访客重叠 → 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppression-intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-i2\",\"visitorId\":\"u1\",\"validFromUtc\":" + (from + 1)
                                + ",\"validUntilUtc\":" + until + "}"))
                .andExpect(status().isConflict());

        // 起止非法 → 422
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppression-intervals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-i3\",\"visitorId\":\"u2\",\"validFromUtc\":" + until
                                + ",\"validUntilUtc\":" + from + "}"))
                .andExpect(status().isUnprocessableEntity());

        // 批量更新版本不一致 → 409
        mockMvc.perform(post("/api/exposure/campaigns/ch/suppression-list:batch-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-b1\",\"expectedVersion\":9,\"items\":["
                                + "{\"visitorId\":\"u3\",\"validFromUtc\":" + from
                                + ",\"validUntilUtc\":" + until + "}]}"))
                .andExpect(status().isConflict());

        // 命中抑制的曝光申请 → 200 SUPPRESSED
        mockMvc.perform(post("/api/exposure/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"h-a1\",\"campaignId\":\"ch\",\"visitorId\":\"u1\","
                                + "\"placementId\":\"slot-1\",\"requestAtUtc\":" + from + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.reason").value("SUPPRESSED_BY_INTERVAL"))
                .andExpect(jsonPath("$.resultType").value("SUPPRESSED"));

        // 抑制状态与区间历史查询
        mockMvc.perform(get("/api/exposure/campaigns/ch/suppression-status").param("visitorId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suppressed").value(true))
                .andExpect(jsonPath("$.reason").value("SUPPRESSED_BY_INTERVAL"));
        mockMvc.perform(get("/api/exposure/campaigns/ch/suppression-intervals").param("visitorId", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.intervals.length()").value(1));
    }
}
