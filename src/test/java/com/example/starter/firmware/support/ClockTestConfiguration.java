package com.example.starter.firmware.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以固定起点的可控 UTC 时钟替换业务系统时钟，租约到期场景无需真实等待。
 * MutableClock 本身是 Clock，按 Clock 注入时也命中该唯一主 Bean。
 */
@TestConfiguration
public class ClockTestConfiguration {

    public static final Instant START = Instant.parse("2026-10-08T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(START);
    }
}
