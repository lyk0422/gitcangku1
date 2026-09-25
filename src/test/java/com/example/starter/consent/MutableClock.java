package com.example.starter.consent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可控时钟：测试通过 {@link #setInstant} / {@link #advance} 固定或推进 UTC 时间，
 * 用于证明到期比较与“撤销/续签后查询”等时间相关裁决，避免依赖真实睡眠。
 */
public class MutableClock extends Clock {

    private final AtomicReference<Instant> current = new AtomicReference<>(Instant.parse("2026-09-26T00:00:00Z"));

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
        return current.get();
    }

    public void setInstant(Instant instant) {
        current.set(instant);
    }

    public void advance(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }
}
