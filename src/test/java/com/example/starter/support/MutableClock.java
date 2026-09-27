package com.example.starter.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：固定在某一 UTC 时刻，可显式设置或推进，避免真实睡眠与时间漂移。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    /** 设置当前时刻。 */
    public void setInstant(Instant newInstant) {
        this.instant = newInstant;
    }

    /** 向前推进固定时长。 */
    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("测试时钟固定为 UTC");
    }
}
