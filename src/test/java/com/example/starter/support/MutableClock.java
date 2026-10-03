package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试中可推进或重置时间，避免依赖真实时间。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    /**
     * 重置当前时间。
     */
    public void setInstant(Instant newInstant) {
        instant.set(newInstant);
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