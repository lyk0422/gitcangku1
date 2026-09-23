package com.example.starter.consent;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：以可控时钟替换系统墙钟，基线时刻固定为 2026-09-24T00:00:00Z。
 */
@TestConfiguration
public class TestClockConfiguration {

    public static final Instant BASE_TIME = Instant.parse("2026-09-24T00:00:00Z");

    @Bean
    @Primary
    public MutableTimeSource timeSource() {
        return new MutableTimeSource(BASE_TIME);
    }
}
