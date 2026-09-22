package com.example.starter.translation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以可控时钟替换系统时钟（@Primary 注入所有 Clock 依赖处）。
 */
@TestConfiguration
public class TestClockConfig {

    /**
     * 可控时钟，初始固定在 2026-01-01T00:00:00Z。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
