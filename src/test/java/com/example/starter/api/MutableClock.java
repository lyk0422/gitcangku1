package com.example.starter.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控 UTC 时钟：可在场景间显式推进或设定当前时刻，不依赖真实休眠。
 */
public class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(Instant initial) {
        this.now = initial;
    }

    public void setInstant(Instant instant) {
        this.now = instant;
    }

    public void advanceSeconds(long seconds) {
        this.now = now.plusSeconds(seconds);
    }

    @Override
    public Instant instant() {
        return now;
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
