package com.example.starter;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换业务时钟，起始时刻 2026-10-04T08:00:00Z。
 */
@TestConfiguration
public class TestClockConfig {

    @Primary
    @Bean
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-04T08:00:00Z"));
    }
}