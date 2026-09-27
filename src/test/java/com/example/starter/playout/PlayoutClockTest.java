package com.example.starter.playout;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可控时钟单元测试：业务时间测试使用固定时钟，推进时间不靠休眠。
 */
class PlayoutClockTest {

    @Test
    void fixedClockIsControllable() {
        PlayoutClock clock = new PlayoutClock();
        Instant t0 = Instant.parse("2026-09-22T02:00:00Z");
        clock.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        assertThat(clock.nowMs()).isEqualTo(t0.toEpochMilli());

        Instant t1 = t0.plusSeconds(60);
        clock.setClock(Clock.fixed(t1, ZoneOffset.UTC));
        assertThat(clock.nowMs()).isEqualTo(t0.toEpochMilli() + 60_000L);
    }

    @Test
    void millisecondPrecisionIsPreserved() {
        PlayoutClock clock = new PlayoutClock();
        Instant t = Instant.parse("2026-09-22T02:20:00.500Z");
        clock.setClock(Clock.fixed(t, ZoneOffset.UTC));
        assertThat(clock.nowMs() % 1000L).isEqualTo(500L);
    }
}
