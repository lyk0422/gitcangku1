package com.example.starter.weighbridge;

import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换生产 Clock。
 */
@TestConfiguration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-10-04T01:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
    }
}