package com.example.starter.snow;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定的 2026-10-04T12:00:00Z 为默认当前时刻。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-10-04T12:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}