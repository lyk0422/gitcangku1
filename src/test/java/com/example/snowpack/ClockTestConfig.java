package com.example.snowpack;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 测试时钟配置：以可控时钟替换系统时钟。 */
@TestConfiguration
public class ClockTestConfig {

    public static final Instant TEST_START = Instant.parse("2026-10-04T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(TEST_START, ZoneOffset.UTC);
    }
}