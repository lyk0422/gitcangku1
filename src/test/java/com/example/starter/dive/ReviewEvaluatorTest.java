package com.example.starter.dive;

import com.example.starter.dive.domain.Anomaly;
import com.example.starter.dive.domain.AnomalyType;
import com.example.starter.dive.domain.DepthPoint;
import com.example.starter.dive.domain.DiveSession;
import com.example.starter.dive.service.ReviewEvaluator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核评估器单元测试：固定合成时间线，不依赖时钟与数据库。
 */
class ReviewEvaluatorTest {

    private static final Instant BASE = Instant.parse("2026-10-04T10:00:00Z");

    private static DiveSession session() {
        return new DiveSession(1L, "合成训练", BASE, BASE.plusSeconds(40 * 60),
                new BigDecimal("30.00"), 40, 1, BASE.minusSeconds(3600));
    }

    private static DepthPoint point(int seq, int offsetMinutes, String depthM) {
        return new DepthPoint(seq, 1L, seq, BASE.plusSeconds(offsetMinutes * 60L),
                new BigDecimal(depthM), "req-1", BASE);
    }

    /** 合规点位：10:00至10:40每5分钟一点，深度0至30米。 */
    private static List<DepthPoint> compliantPoints() {
        String[] depths = {"0", "10", "20", "28", "24", "16", "9", "4", "0"};
        List<DepthPoint> points = new ArrayList<>();
        for (int i = 0; i < depths.length; i++) {
            points.add(point(i + 1, i * 5, depths[i]));
        }
        return points;
    }

    @Test
    void compliantPointsPass() {
        assertTrue(ReviewEvaluator.evaluate(session(), compliantPoints()).isEmpty());
    }

    @Test
    void boundaryDepthThirtyPasses() {
        List<DepthPoint> points = compliantPoints();
        points.set(3, point(4, 15, "30.00"));
        assertTrue(ReviewEvaluator.evaluate(session(), points).isEmpty());
    }

    @Test
    void exceedDepthRejectedDespiteZeroEnding() {
        // 题干场景：10:15达31米，末点0米也不得通过
        List<DepthPoint> points = compliantPoints();
        points.set(3, point(4, 15, "31.00"));
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertEquals(1, anomalies.size());
        Anomaly anomaly = anomalies.get(0);
        assertEquals(AnomalyType.EXCEED_DEPTH, anomaly.type());
        assertEquals(4, anomaly.fromSeq());
        assertEquals(0, new BigDecimal("31.00").compareTo(anomaly.depthM()));
        assertEquals(BASE.plusSeconds(15 * 60), anomaly.fromTimeUtc());
    }

    @Test
    void negativeDepthRejected() {
        List<DepthPoint> points = compliantPoints();
        points.set(2, point(3, 10, "-1.00"));
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.INVALID_DEPTH, anomalies.get(0).type());
    }

    @Test
    void measureGapDetected() {
        List<DepthPoint> points = compliantPoints();
        points.set(4, point(5, 21, "24")); // 10:15到10:21间隔6分钟
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == AnomalyType.MEASURE_GAP
                && a.fromSeq() == 4 && a.toSeq() == 5));
    }

    @Test
    void timeRegressionDetected() {
        List<DepthPoint> points = compliantPoints();
        points.set(4, point(5, 14, "24")); // 时间倒退到10:14
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == AnomalyType.TIME_REGRESSION
                && a.fromSeq() == 4 && a.toSeq() == 5));
    }

    @Test
    void durationExceededDetected() {
        List<DepthPoint> points = compliantPoints();
        points.add(point(10, 45, "0")); // 总时长45分钟
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == AnomalyType.DURATION_EXCEEDED));
    }

    @Test
    void coverageMissingDetected() {
        List<DepthPoint> points = compliantPoints();
        points.remove(0); // 首点变为10:05，未覆盖计划开始10:00
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), points);
        assertTrue(anomalies.stream().anyMatch(a -> a.type() == AnomalyType.COVERAGE_MISSING));
    }

    @Test
    void emptyPointsRejected() {
        List<Anomaly> anomalies = ReviewEvaluator.evaluate(session(), List.of());
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.NO_POINTS, anomalies.get(0).type());
    }
}