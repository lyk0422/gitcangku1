package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控时钟：测试用，可固定或推进当前时刻，时区固定 UTC。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}