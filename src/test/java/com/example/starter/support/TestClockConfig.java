package com.example.starter.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：用可控时钟替换系统时钟。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-02-01T00:00:00Z"));
    }
}