package com.example.starter.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可控时钟的纯单元测试。
 */
class MutableClockTest {

    @Test
    @DisplayName("固定时刻可读取，推进后立即反映")
    void fixedAndAdvance() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);

        assertThat(clock.instant()).isEqualTo(start);
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);

        clock.advanceSeconds(60);
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-09-01T00:01:00Z"));

        clock.setInstant(Instant.parse("2026-09-30T20:00:00Z"));
        assertThat(clock.instant()).isEqualTo(Instant.parse("2026-09-30T20:00:00Z"));
    }

    @Test
    @DisplayName("withZone 保留当前时刻与指定时区")
    void withZoneKeepsInstantAndZone() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        Clock shifted = clock.withZone(ZoneOffset.ofHours(8));
        assertThat(shifted.instant()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(shifted.getZone()).isEqualTo(ZoneOffset.ofHours(8));
    }
}
