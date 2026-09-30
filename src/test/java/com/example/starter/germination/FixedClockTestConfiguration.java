package com.example.starter.germination;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用固定时钟：签发/放行时刻可被精确断言。
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockTestConfiguration {

    /** 固定时刻：2026-10-01T03:00:00Z（北京时间 11:00）。 */
    public static final Instant FIXED = Instant.parse("2026-10-01T03:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }
}
