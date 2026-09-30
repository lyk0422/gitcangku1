package com.example.starter.playout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：可设定与推进当前时刻，用于验证按时钟派生的占位状态（SCHEDULED/AIRED）。
 * 固定 UTC 区域；线程可见性由 volatile 保证（并发测试中多线程读取）。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advanceMillis(long millis) {
        this.instant = instant.plusMillis(millis);
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
}
