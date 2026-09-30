package com.example.starter.testsupport;

import com.example.starter.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;

/**
 * 用可控时钟替换生产 {@link ClockConfig} 的系统时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public Clock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-01T19:30:00Z"));
    }
}
