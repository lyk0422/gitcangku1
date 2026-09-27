package com.example.starter.translation.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可控 UTC 时钟测试：固定时钟下锚点时间可重复断言，纳秒被截断到微秒。
 */
class TimeSourceTest {

    private final TimeSource timeSource = new TimeSource();

    @AfterEach
    void resetClock() {
        timeSource.reset();
    }

    @Test
    @DisplayName("默认系统 UTC 时钟：取值随当前时间变化")
    void systemClockUtc() {
        Instant first = timeSource.instant();
        Instant second = timeSource.instant();
        assertThat(second).isAfterOrEqualTo(first);
        assertThat(first.getEpochSecond()).isGreaterThan(0);
    }

    @Test
    @DisplayName("替换固定时钟后取值恒定；截断到微秒精度")
    void fixedClockAndMicrosecondTruncation() {
        Instant fixed = Instant.parse("2026-09-27T10:20:30.123456789Z");
        timeSource.setClock(Clock.fixed(fixed, ZoneOffset.UTC));
        assertThat(timeSource.instant()).isEqualTo(fixed);
        assertThat(timeSource.instant()).isEqualTo(fixed);
        assertThat(AnchorSupport.truncateToMicros(fixed))
                .isEqualTo(Instant.parse("2026-09-27T10:20:30.123456Z"));
    }

    @Test
    @DisplayName("reset 后恢复系统时钟")
    void resetRestoresSystemClock() {
        timeSource.setClock(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        timeSource.reset();
        assertThat(timeSource.instant().getEpochSecond()).isGreaterThan(0);
    }
}
