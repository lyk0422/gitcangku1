package com.example.starter.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定 UTC 时刻启动的主时钟 Bean 替换系统时钟，供弃用窗口边界测试推进时间。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时刻（UTC）。 */
    public static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE);
    }
}
