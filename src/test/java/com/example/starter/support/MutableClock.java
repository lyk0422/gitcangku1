package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试可控时钟：固定 UTC 区域，当前时刻可由测试显式设置或推进，用于豁免到期边界断言。
 */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone = ZoneOffset.UTC;

    public MutableClock(Instant initial) {
        this.instant = initial;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void advance(java.time.Duration duration) {
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

    /** 以固定起点 2026-09-30T00:00:00Z 提供可控时钟 Bean，优先于系统时钟。 */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public Clock mutableClock() {
            return new MutableClock(Instant.parse("2026-09-30T00:00:00Z"));
        }
    }
}
