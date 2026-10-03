package com.example.starter.curtailment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟。
 */
@TestConfiguration
public class TestClockConfiguration {

    /** 测试基准时间：2027-06-01T00:00:00Z。 */
    public static final Instant BASE_TIME = Instant.parse("2027-06-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(BASE_TIME);
    }
}