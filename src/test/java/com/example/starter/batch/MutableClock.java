package com.example.starter.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可定点、可按 UTC 日步进，用于效价衰减的时间边界验证。
 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant start) {
        this.instant = start;
    }

    public void set(Instant newInstant) {
        this.instant = newInstant;
    }

    public void plusDays(long days) {
        this.instant = this.instant.plus(Duration.ofDays(days));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
