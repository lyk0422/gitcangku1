package com.example.starter.firmware;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 测试用可控时钟：替代业务 Clock Bean，手工推进时间以验证试启动 60 秒窗口边界。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneId.of("UTC");
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public void set(Instant newInstant) {
        instant = newInstant;
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
        return this;
    }
}
