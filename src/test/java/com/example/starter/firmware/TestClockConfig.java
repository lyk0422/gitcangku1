package com.example.starter.firmware;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试时钟配置：以可控时钟覆盖业务 Clock bean，固定起点为 2026-10-06T00:00:00Z。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
    }
}