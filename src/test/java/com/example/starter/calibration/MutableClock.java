package com.example.starter.calibration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可显式设置当前时刻，避免依赖真实时间。
 */
public final class MutableClock extends Clock {

    private Instant current;

    public MutableClock(Instant initial) {
        this.current = initial;
    }

    public void set(Instant instant) {
        this.current = instant;
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
        return current;
    }
}