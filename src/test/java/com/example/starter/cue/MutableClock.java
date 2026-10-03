package com.example.starter.cue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可控时钟：固定时刻，可按需推进。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;
    private final ZoneId zone;

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant = new AtomicReference<>(initial);
        this.zone = zone;
    }

    public void set(Instant value) {
        instant.set(value);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}