package com.example.starter.support;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 可控时钟，默认固定在 2026-02-01T10:00:00Z。 */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-02-01T10:00:00Z"));
    }
}