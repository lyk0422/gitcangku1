package com.example.starter.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试用可控 UTC 时钟：固定起始时刻，可按测试需要精确推进，避免休眠与真实时间抖动。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant value) {
        this.instant = value;
    }

    public void advance(Duration duration) {
        this.instant = instant.plus(duration);
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

    /** 以可控时钟替换生产系统时钟（@Primary 优先注入）。 */
    @TestConfiguration
    public static class Config {

        public static final Instant INITIAL = Instant.parse("2026-03-01T00:00:00Z");

        @Bean
        @Primary
        public MutableClock controlledClock() {
            return new MutableClock(INITIAL);
        }
    }
}
