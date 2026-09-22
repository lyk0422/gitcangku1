package com.example.starter.translation.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：可定点、可推进，避免时间断言依赖真实时间。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    public void set(Instant value) {
        instant.set(value);
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
        return instant.get();
    }
}
