package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 可变时钟测试配置：芯片测试需要推进提交时刻以覆盖生效前后区间。
 */
@TestConfiguration
public class MutableClockTestConfig {

    /** 测试基准时刻（UTC）。 */
    public static final Instant T0 = Instant.parse("2026-09-22T04:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
