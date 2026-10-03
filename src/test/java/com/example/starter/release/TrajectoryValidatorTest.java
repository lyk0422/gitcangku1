package com.example.starter.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.program.SegmentInfo;
import com.example.starter.reading.TemperatureReading;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 轨迹校核纯函数单元测试：断层、重复时间戳、超差、边界覆盖与各段最坏偏差。
 */
class TrajectoryValidatorTest {

    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    private final List<SegmentInfo> segments = List.of(
            new SegmentInfo(1, 1, T0, T0.plus(20, ChronoUnit.MINUTES), bd("600"), bd("15")),
            new SegmentInfo(2, 2, T0.plus(20, ChronoUnit.MINUTES), T0.plus(40, ChronoUnit.MINUTES),
                    bd("800"), bd("12")));

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private TemperatureReading reading(long id, int offsetMinutes, String temp) {
        return new TemperatureReading(id, 1L, T0.plus(offsetMinutes, ChronoUnit.MINUTES), bd(temp));
    }

    private List<TemperatureReading> validTrajectory() {
        return List.of(
                reading(1, 0, "600"), reading(2, 5, "605"), reading(3, 10, "610"),
                reading(4, 15, "612"), reading(5, 20, "800"), reading(6, 25, "805"),
                reading(7, 30, "808"), reading(8, 35, "810"), reading(9, 40, "800"));
    }

    @Test
    void validTrajectoryPasses() {
        assertTrue(TrajectoryValidator.validate(segments, validTrajectory()).isEmpty());
    }

    @Test
    void eightMinuteGapIsReportedWithSegmentAndTime() {
        List<TemperatureReading> readings = List.of(
                reading(1, 0, "600"), reading(2, 5, "605"), reading(3, 13, "610"),
                reading(4, 18, "612"), reading(5, 20, "800"), reading(6, 25, "805"),
                reading(7, 30, "808"), reading(8, 35, "810"), reading(9, 40, "800"));
        List<TrajectoryValidator.Violation> violations =
                TrajectoryValidator.validate(segments, readings);
        assertEquals(1, violations.size());
        TrajectoryValidator.Violation v = violations.get(0);
        assertEquals("SAMPLING_GAP", v.type());
        assertEquals(1, v.segmentSeq());
        assertEquals(T0.plus(13, ChronoUnit.MINUTES), v.time());
    }

    @Test
    void duplicateTimestampIsReported() {
        List<TemperatureReading> readings = List.of(
                reading(1, 0, "600"), reading(2, 5, "605"), reading(3, 5, "607"),
                reading(4, 10, "610"), reading(5, 15, "612"), reading(6, 20, "800"),
                reading(7, 25, "805"), reading(8, 30, "808"), reading(9, 35, "810"),
                reading(10, 40, "800"));
        List<TrajectoryValidator.Violation> violations =
                TrajectoryValidator.validate(segments, readings);
        assertEquals(1, violations.size());
        assertEquals("DUPLICATE_TIMESTAMP", violations.get(0).type());
        assertEquals(T0.plus(5, ChronoUnit.MINUTES), violations.get(0).time());
    }

    @Test
    void outOfToleranceIsReportedWithSegment() {
        List<TemperatureReading> readings = List.of(
                reading(1, 0, "600"), reading(2, 5, "605"), reading(3, 10, "700"),
                reading(4, 15, "612"), reading(5, 20, "800"), reading(6, 25, "805"),
                reading(7, 30, "808"), reading(8, 35, "810"), reading(9, 40, "800"));
        List<TrajectoryValidator.Violation> violations =
                TrajectoryValidator.validate(segments, readings);
        assertEquals(1, violations.size());
        TrajectoryValidator.Violation v = violations.get(0);
        assertEquals("OUT_OF_TOLERANCE", v.type());
        assertEquals(1, v.segmentSeq());
        assertEquals(T0.plus(10, ChronoUnit.MINUTES), v.time());
    }

    @Test
    void missingBoundariesAreReported() {
        List<TemperatureReading> noStart = validTrajectory().subList(1, 9);
        List<TrajectoryValidator.Violation> v1 = TrajectoryValidator.validate(segments, noStart);
        assertEquals("MISSING_START_BOUNDARY", v1.get(0).type());
        List<TemperatureReading> noEnd = validTrajectory().subList(0, 8);
        List<TrajectoryValidator.Violation> v2 = TrajectoryValidator.validate(segments, noEnd);
        assertEquals("MISSING_END_BOUNDARY", v2.get(0).type());
        List<TrajectoryValidator.Violation> v3 = TrajectoryValidator.validate(segments, List.of());
        assertEquals(2, v3.size());
    }

    @Test
    void endBoundaryBelongsToLastSegment() {
        // 终点 00:40 归属末段（目标800±12），810 在范围内，900 超差
        List<TemperatureReading> ok = List.of(
                reading(1, 35, "810"), reading(2, 40, "810"));
        assertTrue(TrajectoryValidator.validate(segments, ok).stream()
                .noneMatch(v -> v.type().equals("OUT_OF_TOLERANCE")));
        List<TemperatureReading> bad = List.of(
                reading(1, 35, "810"), reading(2, 40, "900"));
        assertTrue(TrajectoryValidator.validate(segments, bad).stream()
                .anyMatch(v -> v.type().equals("OUT_OF_TOLERANCE")
                        && v.segmentSeq() == 2));
    }

    @Test
    void worstDeviationComputedPerSegment() {
        List<TrajectoryValidator.SegmentDeviation> devs =
                TrajectoryValidator.worstDeviations(segments, validTrajectory());
        assertEquals(2, devs.size());
        assertEquals(0, bd("12").compareTo(devs.get(0).worstDeviationC()));
        assertEquals(T0.plus(15, ChronoUnit.MINUTES), devs.get(0).worstAt());
        assertEquals(0, bd("10").compareTo(devs.get(1).worstDeviationC()));
        List<TrajectoryValidator.SegmentDeviation> empty =
                TrajectoryValidator.worstDeviations(segments, List.of());
        assertNull(empty.get(0).worstDeviationC());
    }
}