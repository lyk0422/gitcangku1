package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试时钟配置：以 {@link Primary} 覆盖生产 UTC 系统时钟，
 * 默认固定在 2026-09-01T00:00:00Z。方法名避开生产 Bean 名“clock”，
 * 不依赖 bean-definition-overriding。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant FIXED_NOW = Instant.parse("2026-09-01T00:00:00Z");

    @Bean
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_NOW);
    }

    @Bean
    @Primary
    public Clock testClock(MutableClock mutableClock) {
        return mutableClock;
    }
}
