package com.example.starter.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 左闭右开窗口规则的单元测试。
 */
class TimeWindowsTest {

    private static final Instant T0 = Instant.parse("2026-10-01T20:00:00Z");

    private static Instant at(int minute) {
        return T0.plusSeconds(minute * 60L);
    }

    @Test
    void overlapsWhenInteriorsIntersect() {
        assertThat(TimeWindows.overlaps(at(0), at(60), at(10), at(50))).isTrue();
        assertThat(TimeWindows.overlaps(at(10), at(50), at(0), at(60))).isTrue();
        assertThat(TimeWindows.overlaps(at(0), at(30), at(30), at(60))).isFalse();
        assertThat(TimeWindows.overlaps(at(30), at(60), at(0), at(30))).isFalse();
    }

    @Test
    void fullyContainedWindowFitsGap() {
        assertThat(TimeWindows.fitsWithin(at(10), at(50), at(0), at(60))).isTrue();
        assertThat(TimeWindows.fitsWithin(at(0), at(60), at(0), at(60))).isTrue();
        assertThat(TimeWindows.fitsWithin(at(-10), at(50), at(0), at(60))).isFalse();
        assertThat(TimeWindows.fitsWithin(at(10), at(61), at(0), at(60))).isFalse();
    }
}
