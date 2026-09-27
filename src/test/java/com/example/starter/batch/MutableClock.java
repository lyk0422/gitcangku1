package com.example.starter.batch;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：线程安全地持有当前时刻，测试可显式推进以验证
 * 按 UTC 日计算的效价衰减与放行时点核验。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneOffset.UTC;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    /**
     * 按 UTC 日推进时钟。
     */
    public void plusDays(long days) {
        this.instant = instant.plus(java.time.Duration.ofDays(days));
    }
}
