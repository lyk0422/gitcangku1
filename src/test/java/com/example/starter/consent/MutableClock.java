package com.example.starter.consent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 可控时钟：测试中用于精确推进时间以验证证明到期语义。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    @Override
    public synchronized Instant instant() {
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

    public synchronized void set(Instant newInstant) {
        this.instant = newInstant;
    }

    public synchronized void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }
}
