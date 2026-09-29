package com.example.starter.consent.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 业务时钟单元测试：可控固定时钟与微秒精度截断。
 */
class BusinessClockTest {

    private final BusinessClock clock = new BusinessClock();

    @AfterEach
    void reset() {
        clock.reset();
    }

    @Test
    void pinnedClockReturnsFixedInstant() {
        Instant fixed = Instant.parse("2026-09-29T10:15:30.123456Z");
        clock.pin(fixed);
        assertThat(clock.now()).isEqualTo(fixed);
        assertThat(clock.now()).isEqualTo(fixed);
    }

    @Test
    void nowTruncatesToMicros() {
        // 123456789 纳秒 -> 截断为 123456 微秒
        clock.pin(Instant.parse("2026-09-29T10:15:30.123456789Z"));
        assertThat(clock.now()).isEqualTo(Instant.parse("2026-09-29T10:15:30.123456Z"));
    }

    @Test
    void resetRestoresAdvancingClock() throws InterruptedException {
        clock.pin(Instant.parse("2000-01-01T00:00:00Z"));
        clock.reset();
        Instant first = clock.now();
        Thread.sleep(2);
        assertThat(clock.now()).isAfterOrEqualTo(first.plusNanos(1_000_000));
    }
}
