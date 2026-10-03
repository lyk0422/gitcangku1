package com.example.starter.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定起点替换系统 UTC 时钟，保证拒绝事件时刻可断言。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}