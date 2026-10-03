package com.example.starter.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，初始为 2026-01-01T11:00:00Z。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时间，早于全部合成时隙。 */
    public static final Instant BASE_TIME = Instant.parse("2026-01-01T11:00:00Z");

    /**
     * 可控时钟主 Bean。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}