package com.example.starter;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用可控时钟替换生产 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 可控时钟，初始时刻 2026-01-01T00:00:00Z。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }
}