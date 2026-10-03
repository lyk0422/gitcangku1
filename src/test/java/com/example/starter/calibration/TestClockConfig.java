package com.example.starter.calibration;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfig {

    /** 测试基准时刻：2026-10-05T08:30:00Z。 */
    public static final Instant BASE_NOW = Instant.parse("2026-10-05T08:30:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_NOW);
    }
}