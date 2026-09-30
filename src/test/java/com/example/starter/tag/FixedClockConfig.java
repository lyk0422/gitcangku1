package com.example.starter.tag;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用固定 UTC 时钟，使 closedAt/登记时刻等服务端时间可精确断言。
 */
@TestConfiguration
public class FixedClockConfig {

    public static final Instant FIXED = Instant.parse("2026-03-01T00:00:00Z");

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(FIXED, ZoneOffset.UTC);
    }
}
