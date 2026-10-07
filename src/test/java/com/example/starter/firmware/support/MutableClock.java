package com.example.starter.firmware.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控业务时钟：固定 UTC 时区，测试可显式设置或推进时刻，不依赖真实等待。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
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
        return instant;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }
}
