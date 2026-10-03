package com.example.starter.bridge;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟覆盖生产 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时刻：2026-10-04T09:00:00Z。 */
    public static final Instant BASE_TIME = Instant.parse("2026-10-04T09:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}