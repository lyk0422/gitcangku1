package com.example.starter.firmware;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以可控时钟覆盖业务 Clock Bean，验证试启动 60 秒窗口边界。
 */
@TestConfiguration
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
