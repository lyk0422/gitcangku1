package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 可推进时钟配置：替代固定时钟，测试可按需推进提交时刻。
 */
@TestConfiguration
public class MutableClockTestConfig {

    public static final Instant T0 = Instant.parse("2026-09-22T04:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
