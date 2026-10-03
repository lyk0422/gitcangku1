package com.example.starter.plan;

import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统 UTC 时钟，默认固定在运营日前一日，
 * 各用例可按需拨动。仅作用于测试上下文。
 */
@Configuration
public class TestClockConfig {

    /**
     * 可控时钟，默认 2026-09-21T00:00:00Z（测试运营日 2026-09-22 之前）。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-21T00:00:00Z"));
    }
}