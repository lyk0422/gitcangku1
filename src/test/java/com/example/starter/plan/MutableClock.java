package com.example.starter.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 可控时钟：测试用，可随时设定当前时刻，保证"已开始计划"等时间判定可复现。
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
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}