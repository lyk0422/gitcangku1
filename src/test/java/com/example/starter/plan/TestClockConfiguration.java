package com.example.starter.plan;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，覆盖 15 分钟确认窗口与 5 分钟指令有效期。
 */
@TestConfiguration
public class TestClockConfiguration {

    static final Instant T0 = Instant.parse("2026-10-04T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}