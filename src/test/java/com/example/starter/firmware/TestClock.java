package com.example.starter.firmware;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试显式推进时刻，不依赖真实睡眠。固定返回 UTC。
 */
public class TestClock extends Clock {

    private volatile Instant now;

    public TestClock(Instant initial) {
        this.now = initial;
    }

    public void setInstant(Instant instant) {
        this.now = instant;
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
