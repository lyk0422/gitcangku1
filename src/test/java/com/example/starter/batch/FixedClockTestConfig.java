package com.example.starter.batch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 测试用固定 UTC 时钟：托盘域全部时间戳应取自该可控时钟，断言时间精度与 UTC 语义。
 */
@TestConfiguration
public class FixedClockTestConfig {

    /**
     * 固定时刻（UTC，含纳秒精度），左闭右开区间与历史时间断言均以此为准。
     */
    public static final String FIXED_INSTANT = "2026-03-04T05:06:07.123456789Z";

    @Bean
    @Primary
    public Clock fixedClock() {
        return Clock.fixed(Instant.parse(FIXED_INSTANT), ZoneOffset.UTC);
    }
}
