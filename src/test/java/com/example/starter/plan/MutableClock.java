package com.example.starter.plan;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定起点，可按需推进，避免依赖真实时间与长时间休眠。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public synchronized void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    public synchronized void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
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