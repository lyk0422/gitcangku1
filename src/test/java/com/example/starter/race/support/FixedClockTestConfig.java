package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用固定时钟：时间相关断言稳定可重复。
 */
@TestConfiguration
public class FixedClockTestConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-09-22T04:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
    }
}
