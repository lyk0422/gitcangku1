package com.example.starter.race.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可推进的测试时钟：用于申诉窗口等时间相关场景的可控断言。
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        this.current = new AtomicReference<>(initial);
    }

    public void setInstant(Instant instant) {
        current.set(instant);
    }

    public void advanceMillis(long millis) {
        current.updateAndGet(instant -> instant.plusMillis(millis));
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
        return current.get();
    }
}
