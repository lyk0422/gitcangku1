package com.example.starter.cue;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 用可控时钟替换系统时钟，便于断言开始与结束时间。
 */
@TestConfiguration
public class CueTestConfig {

    public static final Instant T0 = Instant.parse("2026-10-04T10:00:00Z");

    @Bean
    @Primary
    public MutableClock testCueClock() {
        return new MutableClock(T0, ZoneId.of("Asia/Shanghai"));
    }
}