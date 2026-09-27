package com.example.starter.maintenance;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定 UTC 时钟替换生产系统时钟，时间相关断言可精确到时刻与区间边界。
 */
@TestConfiguration
public class ControllableClockConfig {

    public static final Instant FIXED_INSTANT = Instant.parse("2026-06-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(FIXED_INSTANT);
    }
}
