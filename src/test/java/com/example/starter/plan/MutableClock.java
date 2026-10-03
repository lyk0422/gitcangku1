package com.example.starter.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：可随意设定当前时刻，用于覆盖"已开始计划"等时间边界。
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