package com.example.starter.firmware.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 业务时钟：默认跟随 UTC 系统时钟，测试可通过 {@link #setInstant(Instant)} 固定到任意时刻，
 * 验证证书生效/到期等时态行为。所有时间判定均以 UTC 为准。
 */
public class MutableClock extends Clock {

    private final ZoneId zone = ZoneOffset.UTC;

    private volatile Instant fixed;

    public void setInstant(Instant instant) {
        this.fixed = instant;
    }

    public void advance(Duration duration) {
        if (fixed == null) {
            fixed = Instant.now();
        }
        fixed = fixed.plus(duration);
    }

    public void reset() {
        this.fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("业务时钟固定为 UTC");
    }

    @Override
    public Instant instant() {
        return fixed != null ? fixed : Instant.now();
    }
}
