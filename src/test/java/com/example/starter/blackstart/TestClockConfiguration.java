package com.example.starter.blackstart;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，保证时间断言可复现。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-04T08:00:00Z"));
    }
}