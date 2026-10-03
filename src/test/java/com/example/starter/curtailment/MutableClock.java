package com.example.starter.curtailment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试用，可随意设定当前 UTC 瞬时，避免依赖真实时间与长时间休眠。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public synchronized void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    @Override
    public synchronized Instant instant() {
        return instant;
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