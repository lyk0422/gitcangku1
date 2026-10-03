package com.example.starter.race.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用可变时钟：可显式推进 UTC 时刻，
 * 用于验证芯片生效时刻与分段读数提交时刻之间的左闭右开区间归属。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial) {
        this.instant = new AtomicReference<>(initial);
    }

    /** 直接设定当前时刻。 */
    public void setInstant(Instant newInstant) {
        instant.set(newInstant);
    }

    /** 前进指定毫秒数。 */
    public void advanceMillis(long millis) {
        instant.updateAndGet(value -> value.plusMillis(millis));
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
        return instant.get();
    }

    @Override
    public long millis() {
        return instant.get().toEpochMilli();
    }
}
