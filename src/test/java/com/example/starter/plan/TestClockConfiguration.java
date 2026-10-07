package com.example.starter.plan;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟覆盖生产 Clock Bean，初始时刻为运营日 10:00（Asia/Shanghai）。
 */
@TestConfiguration
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-22T02:00:00Z"));
    }
}
