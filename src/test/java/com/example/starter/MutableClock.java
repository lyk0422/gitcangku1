package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 测试用可控时钟：可固定起点并按分钟推进，用于验证 15 分钟确认窗口与 5 分钟指令有效期。
 */
public class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant instant) {
        this.instant = instant;
    }

    public void reset(Instant newInstant) {
        this.instant = newInstant;
    }

    public void plusMinutes(long minutes) {
        this.instant = this.instant.plus(minutes, ChronoUnit.MINUTES);
    }

    @Override
    public Instant instant() {
        return instant;
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