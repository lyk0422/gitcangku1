package com.example.starter.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 测试时钟配置：以固定 UTC 时刻替换系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时间：2026-10-01 12:00:00 UTC，所有测试窗口都安排在当日晚间。 */
    public static final Instant BASE_TIME = Instant.parse("2026-10-01T12:00:00Z");

    @Bean
    @Primary
    public Clock clock() {
        return new MutableClock(BASE_TIME);
    }
}
