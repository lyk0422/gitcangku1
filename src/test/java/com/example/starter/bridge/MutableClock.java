package com.example.starter.bridge;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控 UTC 时钟，线程安全，可在用例中推进或重置时刻。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        this.current = new AtomicReference<>(initial);
    }

    /** 将时钟设置为指定 UTC 时刻。 */
    public void set(Instant newInstant) {
        current.set(newInstant);
    }

    @Override
    public Instant instant() {
        return current.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}