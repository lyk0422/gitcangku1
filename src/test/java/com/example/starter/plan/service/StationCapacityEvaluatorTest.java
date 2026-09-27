package com.example.starter.plan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.StationCapacityEvaluator.CapacityViolation;
import com.example.starter.plan.service.StationCapacityEvaluator.PlatformInterval;
import com.example.starter.plan.service.StationCapacityEvaluator.StationResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 站台容量扫描纯逻辑测试：左闭右开相邻不并发、超卖明细实际值/要求值/差额、
 * 同刻开始按计划键与序号稳定裁决。全部使用固定 UTC 时刻，不依赖系统时钟。
 */
class StationCapacityEvaluatorTest {

    private static final Instant T = Instant.parse("2026-09-22T00:00:00Z");

    private static Instant at(int minute) {
        return T.plusSeconds(minute * 60L);
    }

    private static PlatformInterval interval(String station, String plan, String train, int seq,
                                             int startMin, int endMin) {
        return new PlatformInterval(station, plan, train, seq, at(startMin), at(endMin));
    }

    @Test
    void adjacentIntervalsDoNotOverlapHalfOpen() {
        // [10,20) 与 [20,30) 相邻，容量 1 也不算并发
        Map<String, StationResult> results = StationCapacityEvaluator.evaluate(
                List.of(
                        interval("S1", "P-A", "G1", 0, 10, 20),
                        interval("S1", "P-B", "G2", 0, 20, 30)),
                Map.of("S1", 1));
        StationResult result = results.get("S1");
        assertThat(result.peakConcurrent()).isEqualTo(1);
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void overlappingIntervalsExceedCapacityWithActualAndShortfall() {
        Map<String, StationResult> results = StationCapacityEvaluator.evaluate(
                List.of(
                        interval("S1", "P-A", "G1", 0, 10, 30),
                        interval("S1", "P-B", "G2", 0, 20, 40)),
                Map.of("S1", 1));
        StationResult result = results.get("S1");
        assertThat(result.peakConcurrent()).isEqualTo(2);
        assertThat(result.violations()).hasSize(1);
        CapacityViolation v = result.violations().get(0);
        assertThat(v.scheduleKey()).isEqualTo("P-B");
        assertThat(v.time()).isEqualTo(at(20));
        assertThat(v.concurrent()).isEqualTo(2);
        assertThat(v.capacity()).isEqualTo(1);
        assertThat(v.overflow()).isEqualTo(1);
    }

    @Test
    void capacityTwoAllowsTwoConcurrentThirdOverflows() {
        Map<String, StationResult> results = StationCapacityEvaluator.evaluate(
                List.of(
                        interval("S1", "P-A", "G1", 0, 10, 50),
                        interval("S1", "P-B", "G2", 0, 10, 50),
                        interval("S1", "P-C", "G3", 0, 20, 30)),
                Map.of("S1", 2));
        StationResult result = results.get("S1");
        assertThat(result.peakConcurrent()).isEqualTo(3);
        assertThat(result.violations()).hasSize(1);
        CapacityViolation v = result.violations().get(0);
        assertThat(v.scheduleKey()).isEqualTo("P-C");
        assertThat(v.concurrent()).isEqualTo(3);
        assertThat(v.capacity()).isEqualTo(2);
        assertThat(v.overflow()).isEqualTo(1);
    }

    @Test
    void simultaneousStartsAreOrderedByScheduleKeyThenSeq() {
        // 两个同刻开始，容量仅 1：按 (scheduleKey, seq) 字典序裁决超限者
        Map<String, StationResult> results = StationCapacityEvaluator.evaluate(
                List.of(
                        interval("S1", "P-Z", "G9", 0, 10, 30),
                        interval("S1", "P-A", "G1", 0, 10, 30)),
                Map.of("S1", 1));
        assertThat(results.get("S1").violations()).extracting(CapacityViolation::scheduleKey)
                .containsExactly("P-Z");
    }

    @Test
    void stationsAreEvaluatedIndependently() {
        Map<String, StationResult> results = StationCapacityEvaluator.evaluate(
                List.of(
                        interval("S1", "P-A", "G1", 0, 10, 30),
                        interval("S1", "P-B", "G2", 0, 10, 30),
                        interval("S2", "P-C", "G3", 0, 10, 30)),
                Map.of("S1", 1, "S2", 2));
        assertThat(results.get("S1").violations()).hasSize(1);
        assertThat(results.get("S2").violations()).isEmpty();
        assertThat(results.get("S2").peakConcurrent()).isEqualTo(1);
    }

    @Test
    void dwellMinutesTruncatesTowardsZero() {
        assertThat(StationCapacityEvaluator.dwellMinutes(at(0), at(5))).isEqualTo(5);
        assertThat(StationCapacityEvaluator.dwellMinutes(at(0), at(5).plusSeconds(59)))
                .isEqualTo(5);
    }
}
