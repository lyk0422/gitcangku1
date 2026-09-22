package com.example.starter.testsupport;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定起始时刻的 {@link MutableClock} 替换系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 固定起始时刻：2026-09-22T10:00:00Z。 */
    public static final Instant START = Instant.parse("2026-09-22T10:00:00Z");

    @Bean
    @Primary
    public Clock testClock() {
        return new MutableClock(START);
    }
}
