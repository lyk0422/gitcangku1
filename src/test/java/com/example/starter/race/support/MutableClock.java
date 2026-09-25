package com.example.starter.race.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：可在测试中推进时间，用于验证 PASS 检录有效期与起跑门禁。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this.instant = initial;
        this.zone = ZoneOffset.UTC;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    /** 把当前时间向前推进指定毫秒数。 */
    public void advanceMillis(long millis) {
        this.instant = instant.plusMillis(millis);
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
}
