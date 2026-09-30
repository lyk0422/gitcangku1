package com.example.starter.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.config.AppConfig;
import com.example.starter.domain.ApplicationStatus;
import com.example.starter.web.ApiException;
import com.example.starter.web.ApplicationResponse;
import com.example.starter.web.ApplyRequest;
import com.example.starter.web.ReviewRequest;
import com.example.starter.web.TidePublishRequest;
import com.example.starter.web.TideVersionResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2（MODE=MySQL）集成测试：覆盖申请成功、422 校验失败、可区分拒绝原因、
 * 潮高修订联动与快照保留、复核、幂等与并发提交。
 */
@SpringBootTest
class BerthAllocationServiceH2Test {

    private static final LocalDateTime T06 = LocalDateTime.of(2026, 10, 1, 6, 0);
    private static final LocalDateTime T08 = LocalDateTime.of(2026, 10, 1, 8, 0);
    private static final LocalDateTime T10 = LocalDateTime.of(2026, 10, 1, 10, 0);
    private static final LocalDateTime T12 = LocalDateTime.of(2026, 10, 1, 12, 0);
    private static final LocalDateTime T13 = LocalDateTime.of(2026, 10, 1, 13, 0);
    private static final LocalDateTime T14 = LocalDateTime.of(2026, 10, 1, 14, 0);
    private static final LocalDateTime BEFORE_START = LocalDateTime.of(2026, 10, 1, 7, 0);

    @Autowired
    private BerthAllocationService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock mutableClock;

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        MutableClock clock() {
            return new MutableClock(BEFORE_START);
        }
    }

    /** 可控时钟，时间相关用例不依赖真实当前时间。 */
    static class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone = AppConfig.ZONE;

        MutableClock(LocalDateTime time) {
            setTime(time);
        }

        void setTime(LocalDateTime time) {
            this.instant = time.atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @BeforeEach
    void resetClock() {
        mutableClock.setTime(BEFORE_START);
    }

    @AfterEach
    void cleanScenarioData() {
        jdbcTemplate.update("DELETE FROM review_record");
        jdbcTemplate.update("DELETE FROM berth_application");
        jdbcTemplate.update("DELETE FROM tide_segment");
        jdbcTemplate.update("DELETE FROM tide_version");
    }

    private TideVersionResponse publishV1() {
        return service.publishTide(new TidePublishRequest(T06, T12, List.of(
                segment(T06, T08, "1.20"),
                segment(T08, T10, "0.70"),
                segment(T10, T12, "1.00"))));
    }

    private void publishLowRevision() {
        service.publishTide(new TidePublishRequest(T06, T12, List.of(
                segment(T06, T08, "0.80"),
                segment(T08, T10, "0.50"),
                segment(T10, T12, "0.30"))));
    }

    private void publishGoodRevision() {
        service.publishTide(new TidePublishRequest(T06, T12, List.of(
                segment(T06, T08, "1.00"),
                segment(T08, T10, "0.90"),
                segment(T10, T12, "0.60"))));
    }

    private TidePublishRequest.Segment segment(LocalDateTime start, LocalDateTime end, String height) {
        return new TidePublishRequest.Segment(start, end, new BigDecimal(height));
    }

    private ApplyRequest shipRequest(String requestId, String vesselName, String draft,
                                     String margin, String berth,
                                     LocalDateTime start, LocalDateTime end) {
        return new ApplyRequest(requestId, vesselName, new BigDecimal(draft), new BigDecimal(margin),
                berth, start, end);
    }

    @Test
    void mainScenario_confirmedAt07ThenRevision05PendingReleasesWindowThenReviewApproved() {
        publishV1();
        ApplicationResponse confirmed = service.apply(
                shipRequest("REQ-A", "合成船9.2", "9.20", "0.20", "B-01", T08, T10));

        // 静态 8.8 + 最低潮高 0.7 = 9.5 >= 9.2+0.2，余量 0.1，允许靠泊
        assertEquals(ApplicationStatus.CONFIRMED.name(), confirmed.status());
        assertNull(confirmed.rejectionCode());
        assertEquals(1L, confirmed.tideVersionNo());
        assertEquals(0, new BigDecimal("0.70").compareTo(confirmed.minTideHeight()));
        assertEquals(0, new BigDecimal("9.50").compareTo(confirmed.availableDepth()));
        assertEquals(0, new BigDecimal("0.10").compareTo(confirmed.clearance()));
        assertEquals(1L, confirmed.snapTideVersionNo());
        assertNotNull(confirmed.confirmedAt());

        // 潮高修订为 0.5，尚未开始：待复核、释放占用，原确认快照保留
        publishLowRevision();
        ApplicationResponse pending = service.getApplication("REQ-A");
        assertEquals(ApplicationStatus.PENDING_REVIEW.name(), pending.status());
        assertEquals(2L, pending.tideVersionNo());
        assertEquals(0, new BigDecimal("0.50").compareTo(pending.minTideHeight()));
        assertEquals(0, new BigDecimal("-0.10").compareTo(pending.clearance()));
        assertEquals(1L, pending.snapTideVersionNo(), "原确认决策快照版本不被自动改写");
        assertEquals(0, new BigDecimal("0.70").compareTo(pending.snapMinTideHeight()));
        assertEquals(0, new BigDecimal("9.50").compareTo(pending.snapAvailableDepth()));
        assertEquals(confirmed.confirmedAt(), pending.confirmedAt(),
                "待复核释放占用，但保留最近一次确认时间与决策快照");
        assertEquals(0, countConfirmedAtBerth("B-01"), "待复核不占泊位，原窗口已释放");

        // 不占用原窗口：浅吃水船可确认同一窗口
        ApplicationResponse taker = service.apply(
                shipRequest("REQ-TAKER", "合成船浅吃水", "8.00", "0.20", "B-01", T08, T10));
        assertEquals(ApplicationStatus.CONFIRMED.name(), taker.status());
        assertEquals(2L, taker.tideVersionNo());

        // 发布恢复深度的新版本 v3，REQ-A 深度合格但原窗口被 REQ-TAKER 占用：可区分的占用拒绝
        publishGoodRevision();
        ApiException occupied = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("REQ-A", true)));
        assertEquals(422, occupied.getStatus().value());
        assertEquals("BERTH_OCCUPIED", occupied.getCode());
        assertEquals(ApplicationStatus.PENDING_REVIEW.name(),
                service.getApplication("REQ-A").status(), "失败复核不改状态");
        assertEquals(0L, countReviewRecords(), "失败复核不留幂等记录");

        // 另一条申请走复核通过路径：v3 下 10-12 潮高 0.6，8.8+0.6=9.4 恰好合格
        ApplicationResponse d = service.apply(
                shipRequest("REQ-D", "合成船D", "9.20", "0.20", "B-01", T10, T12));
        assertEquals(ApplicationStatus.CONFIRMED.name(), d.status());
        assertEquals(3L, d.tideVersionNo());

        // v4 低潮高把 REQ-D 与 REQ-TAKER 置为待复核并释放
        publishLowRevision();
        assertEquals(ApplicationStatus.PENDING_REVIEW.name(),
                service.getApplication("REQ-D").status());
        // v5 潮高恢复，待复核不自动确认，需人工复核
        publishGoodRevision();
        ApplicationResponse reviewed = service.review(new ReviewRequest("REQ-D", true));
        assertEquals(ApplicationStatus.CONFIRMED.name(), reviewed.status());
        assertEquals(5L, reviewed.tideVersionNo());
        assertEquals(0, new BigDecimal("0.60").compareTo(reviewed.minTideHeight()));
        assertEquals(0, new BigDecimal("0.00").compareTo(reviewed.clearance()));
        assertEquals(5L, reviewed.snapTideVersionNo());
        assertNotNull(reviewed.confirmedAt());
        assertNotNull(reviewed.reviewedAt());
        // REQ-TAKER 吃水 8.0 米，在低版本下仍合格故始终确认；复核后 REQ-D 也恢复占泊，两窗口不重叠
        assertEquals(2, countConfirmedAtBerth("B-01"));
    }

    @Test
    void depthInsufficientRejectedWithDistinctCodeAndDoesNotOccupyBerth() {
        service.publishTide(new TidePublishRequest(T06, T12, List.of(segment(T06, T12, "0.30"))));
        ApplicationResponse rejected = service.apply(
                shipRequest("REQ-R", "合成船深吃水", "9.20", "0.20", "B-01", T08, T10));
        assertEquals(ApplicationStatus.REJECTED.name(), rejected.status());
        assertEquals("DEPTH_INSUFFICIENT", rejected.rejectionCode());
        assertNotNull(rejected.rejectionReason());
        assertEquals(0, new BigDecimal("9.10").compareTo(rejected.availableDepth()));
        assertEquals(0, new BigDecimal("-0.30").compareTo(rejected.clearance()));
        assertNull(rejected.confirmedAt());

        ApplicationResponse shallow = service.apply(
                shipRequest("REQ-S", "合成船浅吃水", "8.00", "0.20", "B-01", T08, T10));
        assertEquals(ApplicationStatus.CONFIRMED.name(), shallow.status(),
                "深度不足的拒绝不占用泊位，浅吃水船可使用同一窗口");
    }

    @Test
    void reviewApproveAfterRevisionUsesDistinctRevisionDepthCode() {
        publishV1();
        service.apply(shipRequest("REQ-REV", "合成船REV", "9.20", "0.20", "B-01", T08, T10));
        publishLowRevision();
        assertEquals(ApplicationStatus.PENDING_REVIEW.name(),
                service.getApplication("REQ-REV").status());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("REQ-REV", true)));
        assertEquals(422, ex.getStatus().value());
        assertEquals("TIDE_REVISION_DEPTH_INSUFFICIENT", ex.getCode(),
                "潮高修订后深度不足的复核拒绝原因必须可区分");
        assertEquals(ApplicationStatus.PENDING_REVIEW.name(),
                service.getApplication("REQ-REV").status());
    }

    @Test
    void manualRejectEndsApplicationAndFreesBerth() {
        publishV1();
        service.apply(shipRequest("REQ-M", "合成船M", "9.20", "0.20", "B-01", T08, T10));
        publishLowRevision();
        ApplicationResponse rejected = service.review(new ReviewRequest("REQ-M", false));
        assertEquals(ApplicationStatus.REJECTED.name(), rejected.status());
        assertEquals("MANUAL_REJECT", rejected.rejectionCode());
        assertEquals(0, countConfirmedAtBerth("B-01"));

        // 窗口释放后其他船可确认：当前低版本 08-10 潮高 0.5，9.0+0.2=9.2 <= 8.8+0.5=9.3 合格
        ApplicationResponse other = service.apply(
                shipRequest("REQ-M2", "合成船M2", "9.00", "0.20", "B-01", T08, T10));
        assertEquals(ApplicationStatus.CONFIRMED.name(), other.status(),
                "人工拒绝后窗口释放，低版本下仍合格的船舶可确认占泊");
    }

    @Test
    void startedApplicationKeepsDecisionSnapshotAfterRevision() {
        publishV1();
        ApplicationResponse a = service.apply(
                shipRequest("REQ-STARTED", "合成船已开始", "9.20", "0.20", "B-01", T08, T10));
        assertEquals(ApplicationStatus.CONFIRMED.name(), a.status());

        // 将时钟拨到靠泊开始之后再发布修订：已开始记录保留原决策快照
        mutableClock.setTime(LocalDateTime.of(2026, 10, 1, 9, 0));
        publishLowRevision();

        ApplicationResponse unchanged = service.getApplication("REQ-STARTED");
        assertEquals(ApplicationStatus.CONFIRMED.name(), unchanged.status(),
                "已开始的靠泊不被修订自动改写");
        assertEquals(1L, unchanged.tideVersionNo());
        assertEquals(1L, unchanged.snapTideVersionNo());
        assertEquals(0, new BigDecimal("0.70").compareTo(unchanged.minTideHeight()));
        assertEquals(1, countConfirmedAtBerth("B-01"), "已开始靠泊继续占用");
    }

    @Test
    void invalidRequestsReturn422AndLeaveNoRecords() {
        publishV1();

        ApiException gap = assertThrows(ApiException.class, () -> service.apply(
                shipRequest("REQ-GAP", "合成船G", "9.20", "0.20", "B-01", T13, T14)));
        assertEquals(422, gap.getStatus().value());
        assertEquals("TIDE_GAP", gap.getCode());

        ApiException badTime = assertThrows(ApiException.class, () -> service.apply(
                shipRequest("REQ-TIME", "合成船T", "9.20", "0.20", "B-01", T10, T08)));
        assertEquals(422, badTime.getStatus().value());
        assertEquals("INVALID_TIME_RANGE", badTime.getCode());

        ApiException badDraft = assertThrows(ApiException.class, () -> service.apply(
                shipRequest("REQ-DRAFT", "合成船D", "0.00", "0.20", "B-01", T08, T10)));
        assertEquals(422, badDraft.getStatus().value());
        assertEquals("INVALID_DRAFT", badDraft.getCode());

        ApiException noBerth = assertThrows(ApiException.class, () -> service.apply(
                shipRequest("REQ-BERTH", "合成船B", "9.20", "0.20", "B-NOPE", T08, T10)));
        assertEquals(422, noBerth.getStatus().value());
        assertEquals("BERTH_NOT_FOUND", noBerth.getCode());

        // 潮高区间存在缺口
        ApiException publishGap = assertThrows(ApiException.class, () -> service.publishTide(
                new TidePublishRequest(T06, T12, List.of(
                        segment(T06, T08, "1.00"),
                        segment(T08.plusMinutes(30), T12, "1.00")))));
        assertEquals(422, publishGap.getStatus().value());
        assertEquals("TIDE_GAP", publishGap.getCode());

        // 非正时长区间
        ApiException nonPositive = assertThrows(ApiException.class, () -> service.publishTide(
                new TidePublishRequest(T06, T12, List.of(
                        segment(T06, T08, "1.00"),
                        segment(T08, T08, "1.00"),
                        segment(T08, T12, "1.00")))));
        assertEquals(422, nonPositive.getStatus().value());

        // 版本范围非正
        ApiException badRange = assertThrows(ApiException.class, () -> service.publishTide(
                new TidePublishRequest(T12, T06, List.of(segment(T06, T12, "1.00")))));
        assertEquals(422, badRange.getStatus().value());

        assertEquals(1L, countVersions(), "失败发布不留下潮高版本");
        assertEquals(0L, countApplications(), "失败申请不留下半条分配或幂等记录");
    }

    @Test
    void sameRequestReplaysFirstResultDifferentParamsConflict() {
        publishV1();
        ApplyRequest first = shipRequest("REQ-IDEM", "合成船I", "9.20", "0.20", "B-01", T08, T10);
        ApplicationResponse r1 = service.apply(first);
        ApplicationResponse r2 = service.apply(first);
        assertEquals(ApplicationStatus.CONFIRMED.name(), r1.status());
        assertEquals(r1.tideVersionNo(), r2.tideVersionNo());
        assertEquals(r1.clearance(), r2.clearance());
        assertEquals(1L, countApplications());

        ApplyRequest different = shipRequest("REQ-IDEM", "合成船I", "8.50", "0.20", "B-01", T08, T10);
        ApiException conflict = assertThrows(ApiException.class, () -> service.apply(different));
        assertEquals(409, conflict.getStatus().value());
        assertEquals(1L, countApplications(), "异参重放不新增记录");

        // 复核幂等：首次结论重放，异参 409
        publishLowRevision();
        ApplicationResponse rejected = service.review(new ReviewRequest("REQ-IDEM", false));
        assertEquals(ApplicationStatus.REJECTED.name(), rejected.status());
        assertEquals("MANUAL_REJECT", rejected.rejectionCode());
        ApplicationResponse replay = service.review(new ReviewRequest("REQ-IDEM", false));
        assertEquals(ApplicationStatus.REJECTED.name(), replay.status());
        assertEquals(1L, countReviewRecords());

        ApiException reviewConflict = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("REQ-IDEM", true)));
        assertEquals(409, reviewConflict.getStatus().value());
        assertEquals(1L, countReviewRecords());
    }

    @Test
    void reviewOnlyAllowedWhenPendingReview() {
        publishV1();
        service.apply(shipRequest("REQ-X", "合成船X", "8.00", "0.20", "B-01", T08, T10));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("REQ-X", true)));
        assertEquals(422, ex.getStatus().value());
        assertEquals("REVIEW_NOT_REQUIRED", ex.getCode());

        ApiException missing = assertThrows(ApiException.class,
                () -> service.review(new ReviewRequest("REQ-MISSING", true)));
        assertEquals(404, missing.getStatus().value());
        assertEquals(0L, countReviewRecords(), "失败复核不留幂等记录");
    }

    @Test
    void concurrentApplyAndTideRevisionNeverConfirmAgainstStaleVersion() throws Exception {
        publishV1();
        int iterations = 12;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                String requestId = "REQ-CONC-" + i;
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);

                Future<?> applyFuture = pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    service.apply(shipRequest(requestId, "合成船并发", "9.20", "0.20",
                            "B-01", T08, T10));
                });
                Future<?> publishFuture = pool.submit(() -> {
                    ready.countDown();
                    await(start);
                    publishLowRevision();
                });
                start.countDown();
                applyFuture.get(20, TimeUnit.SECONDS);
                publishFuture.get(20, TimeUnit.SECONDS);

                ApplicationResponse finalState = service.getApplication(requestId);
                assertEquals(Integer.valueOf(1), jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM berth_application WHERE request_id = ?",
                        Integer.class, requestId), "申请记录唯一，无半条数据");
                if (ApplicationStatus.CONFIRMED.name().equals(finalState.status())) {
                    // 确认只能基于已提交的低版本；低版本对 9.2 米吃水船深度不足，因此不得出现确认
                    throw new AssertionError(
                            "低潮高版本下深吃水船不得保持 CONFIRMED：" + finalState);
                }
                assertTrue(
                        ApplicationStatus.PENDING_REVIEW.name().equals(finalState.status())
                                || ApplicationStatus.REJECTED.name().equals(finalState.status()),
                        "申请先按 v1 确认则被修订为待复核，按 v2 校核则直接拒绝，实际："
                                + finalState.status());
                if (ApplicationStatus.REJECTED.name().equals(finalState.status())) {
                    assertEquals("DEPTH_INSUFFICIENT", finalState.rejectionCode(),
                            "按低版本直接校核的拒绝必须带可区分原因");
                } else {
                    assertNull(finalState.rejectionCode());
                }
                jdbcTemplate.update("DELETE FROM berth_application WHERE request_id = ?", requestId);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSameRequestWithDifferentParamsExactlyOneWins() throws Exception {
        publishV1();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> same = pool.submit(() -> {
                ready.countDown();
                await(start);
                service.apply(shipRequest("REQ-DUP", "合成船DUP", "9.20", "0.20",
                        "B-01", T08, T10));
            });
            Future<?> different = pool.submit(() -> {
                ready.countDown();
                await(start);
                service.apply(shipRequest("REQ-DUP", "合成船DUP", "7.00", "0.20",
                        "B-01", T08, T10));
            });
            start.countDown();

            boolean sameOk;
            boolean differentOk;
            try {
                same.get(20, TimeUnit.SECONDS);
                sameOk = true;
            } catch (Exception ex) {
                sameOk = false;
            }
            try {
                different.get(20, TimeUnit.SECONDS);
                differentOk = true;
            } catch (Exception ex) {
                differentOk = false;
            }
            assertTrue(sameOk ^ differentOk, "同参与异参并发提交时恰好一个成功");
            assertEquals(ApplicationStatus.CONFIRMED.name(),
                    service.getApplication("REQ-DUP").status());
            assertEquals(1L, countApplications(), "唯一约束加行锁保证只有一条分配记录");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timelineReportsTideVersionAndApplications() {
        publishV1();
        service.apply(shipRequest("REQ-TL", "合成船时间轴", "9.20", "0.20", "B-01", T08, T10));
        var timeline = service.berthTimeline("B-01");
        assertEquals("B-01", timeline.berthCode());
        assertEquals(0, new BigDecimal("8.80").compareTo(timeline.staticDepth()));
        assertNotNull(timeline.currentTideVersion());
        assertEquals(1L, timeline.currentTideVersion().versionNo());
        assertEquals(3, timeline.currentTideVersion().segments().size());
        assertEquals(1, timeline.applications().size());
        assertEquals("REQ-TL", timeline.applications().get(0).requestId());
        assertEquals("0.70", timeline.applications().get(0).minTideHeight().toPlainString());
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private long countVersions() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tide_version", Long.class);
    }

    private long countApplications() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM berth_application", Long.class);
    }

    private long countReviewRecords() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_record", Long.class);
    }

    private int countConfirmedAtBerth(String berth) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM berth_application WHERE berth_code = ? AND status = 'CONFIRMED'",
                Integer.class, berth);
    }
}
