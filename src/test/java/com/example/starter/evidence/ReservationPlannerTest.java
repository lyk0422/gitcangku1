package com.example.starter.evidence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检验台容量规划纯逻辑单元测试：左闭右开重叠判定、首尾相接不重叠、扫描线峰值与稳定分段。
 */
class ReservationPlannerTest {

    private static final LocalDateTime T = LocalDateTime.parse("2026-09-22T08:00:00");

    private ReservationPlanner.Interval interval(long startHour, long endHour) {
        return new ReservationPlanner.Interval(T.plusHours(startHour), T.plusHours(endHour));
    }

    @Test
    void overlapsUsesHalfOpenInterval() {
        // 首尾相接 [8,9) 与 [9,10) 不重叠
        assertThat(ReservationPlanner.overlaps(T.plusHours(8), T.plusHours(9),
                T.plusHours(9), T.plusHours(10))).isFalse();
        // 部分重叠
        assertThat(ReservationPlanner.overlaps(T.plusHours(8), T.plusHours(10),
                T.plusHours(9), T.plusHours(11))).isTrue();
        // 一方完全包含另一方
        assertThat(ReservationPlanner.overlaps(T.plusHours(8), T.plusHours(12),
                T.plusHours(9), T.plusHours(10))).isTrue();
        // 完全分离
        assertThat(ReservationPlanner.overlaps(T.plusHours(8), T.plusHours(9),
                T.plusHours(10), T.plusHours(11))).isFalse();
        // 仅在左端点相接（A.end == B.start）不重叠；A.start == B.end 也不重叠
        assertThat(ReservationPlanner.overlaps(T.plusHours(10), T.plusHours(11),
                T.plusHours(8), T.plusHours(10))).isFalse();
    }

    @Test
    void peakLoadCountsParallelOccupancyWithHalfOpenBoundary() {
        // 三个时段：[8,9)、[9,10) 首尾相接，[8,10) 与前两者并行：峰值为 2 而非 3
        int peak = ReservationPlanner.peakLoad(List.of(
                interval(8, 9), interval(9, 10), interval(8, 10)));
        assertThat(peak).isEqualTo(2);
    }

    @Test
    void emptyIntervalsHaveZeroPeakAndNoSegments() {
        assertThat(ReservationPlanner.peakLoad(List.of())).isZero();
        assertThat(ReservationPlanner.segments(List.of())).isEmpty();
    }

    @Test
    void segmentsMergeAdjacentSameLoadAndStaySorted() {
        List<ReservationPlanner.Segment> segments = ReservationPlanner.segments(List.of(
                interval(8, 9), interval(9, 10)));
        // 首尾相接且占用恒为 1，合并为一个分段
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).start()).isEqualTo(T.plusHours(8));
        assertThat(segments.get(0).end()).isEqualTo(T.plusHours(10));
        assertThat(segments.get(0).load()).isEqualTo(1);
    }

    @Test
    void segmentsReportLoadStepsForCapacityDecision() {
        List<ReservationPlanner.Segment> segments = ReservationPlanner.segments(List.of(
                interval(8, 10), interval(9, 11), interval(10, 12)));
        // [9,10) 与 [10,11) 占用恒为 2 且首尾相接，合并为 [9,11)；共 3 段
        assertThat(segments).hasSize(3);
        assertThat(segments.get(0).start()).isEqualTo(T.plusHours(8));
        assertThat(segments.get(0).end()).isEqualTo(T.plusHours(9));
        assertThat(segments.get(0).load()).isEqualTo(1);
        assertThat(segments.get(1).start()).isEqualTo(T.plusHours(9));
        assertThat(segments.get(1).end()).isEqualTo(T.plusHours(11));
        assertThat(segments.get(1).load()).isEqualTo(2);
        assertThat(segments.get(2).start()).isEqualTo(T.plusHours(11));
        assertThat(segments.get(2).end()).isEqualTo(T.plusHours(12));
        assertThat(segments.get(2).load()).isEqualTo(1);
    }

    @Test
    void clipKeepsOverlapInsideWindowAndDropsDisjoint() {
        ReservationPlanner.Interval clipped = ReservationPlanner.clip(
                interval(8, 10), T.plusHours(9), T.plusHours(12));
        assertThat(clipped).isNotNull();
        assertThat(clipped.start()).isEqualTo(T.plusHours(9));
        assertThat(clipped.end()).isEqualTo(T.plusHours(10));

        // 首尾相接无正长度交集，返回 null
        assertThat(ReservationPlanner.clip(interval(8, 9), T.plusHours(9), T.plusHours(10)))
                .isNull();
        assertThat(ReservationPlanner.clip(interval(11, 12), T.plusHours(9), T.plusHours(11)))
                .isNull();
    }
}
