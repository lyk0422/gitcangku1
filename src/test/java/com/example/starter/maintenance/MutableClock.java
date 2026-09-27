package com.example.starter.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定在 UTC，可在测试中显式重置与推进，避免对真实时间的不确定等待。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void reset(Instant value) {
        this.instant = value;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
