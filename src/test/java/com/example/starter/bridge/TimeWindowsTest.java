package com.example.starter.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 左闭右开区间相交判定的纯单元测试。
 */
class TimeWindowsTest {

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void overlappingIntervalsIntersect() {
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:00:00Z"), at("2026-10-04T09:10:00Z"),
                at("2026-10-04T09:05:00Z"), at("2026-10-04T09:15:00Z"))).isTrue();
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:05:00Z"), at("2026-10-04T09:15:00Z"),
                at("2026-10-04T09:00:00Z"), at("2026-10-04T09:10:00Z"))).isTrue();
    }

    @Test
    void adjacentIntervalsDoNotIntersect() {
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:00:00Z"), at("2026-10-04T09:10:00Z"),
                at("2026-10-04T09:10:00Z"), at("2026-10-04T09:20:00Z"))).isFalse();
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:10:00Z"), at("2026-10-04T09:20:00Z"),
                at("2026-10-04T09:00:00Z"), at("2026-10-04T09:10:00Z"))).isFalse();
    }

    @Test
    void openEndedIntervalIntersectsAnyLaterStart() {
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:05:00Z"), null,
                at("2026-10-04T09:15:00Z"), at("2026-10-04T09:25:00Z"))).isTrue();
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T09:15:00Z"), at("2026-10-04T09:25:00Z"),
                at("2026-10-04T09:05:00Z"), null)).isTrue();
        assertThat(TimeWindows.overlaps(
                at("2026-10-04T08:00:00Z"), at("2026-10-04T08:10:00Z"),
                at("2026-10-04T09:05:00Z"), null)).isFalse();
    }
}