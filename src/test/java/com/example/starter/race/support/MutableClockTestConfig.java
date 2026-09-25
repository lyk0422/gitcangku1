package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试用可变时钟配置：暴露同一个 {@link MutableClock} Bean，便于推进时间验证有效期。
 */
@TestConfiguration
public class MutableClockTestConfig {

    public static final Instant INITIAL_INSTANT = Instant.parse("2026-09-25T04:00:00Z");

    private final MutableClock clock = new MutableClock(INITIAL_INSTANT);

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return clock;
    }
}
