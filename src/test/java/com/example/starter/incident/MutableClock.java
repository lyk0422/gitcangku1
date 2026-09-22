package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：set 固定到指定 UTC 时刻，reset 回系统 UTC。
 * 以 @Primary 测试 Bean 注入，覆盖生产 ClockConfig 的系统时钟。
 */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    /** 固定时钟到指定 UTC 时刻；后续 clock.instant() 恒返回该时刻。 */
    public void set(Instant instant) {
        this.fixed = instant;
    }

    /** 恢复为系统 UTC 时钟。 */
    public void reset() {
        this.fixed = null;
    }

    @Override
    public Instant instant() {
        Instant snapshot = fixed;
        return snapshot != null ? snapshot : Instant.now();
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
