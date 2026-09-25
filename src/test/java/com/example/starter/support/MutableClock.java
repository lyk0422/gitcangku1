package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：固定时区 UTC，可随时推进或重置当前时刻。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
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
