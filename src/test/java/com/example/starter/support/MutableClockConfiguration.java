package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定起点提供可变的 UTC 时钟，替代系统时钟，
 * 使弃用窗口的生效/撤销/锁定裁决在测试中可精确控制。
 */
@TestConfiguration
public class MutableClockConfiguration {

    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
