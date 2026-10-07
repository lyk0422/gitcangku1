package com.example.starter.plan;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：线程安全地读取/设置当前时刻，替代系统时钟验证时间相关分支。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
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
