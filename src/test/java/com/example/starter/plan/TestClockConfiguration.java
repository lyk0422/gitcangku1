package com.example.starter.plan;

import java.time.Instant;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换应用统一时钟，默认固定在运营日前一日，
 * 各用例按需显式设置时刻，用例间互不影响。
 */
@Configuration
public class TestClockConfiguration {

    /**
     * 可控主时钟，默认 2026-09-21T00:00:00Z（早于全部测试运营日）。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(Instant.parse("2026-09-21T00:00:00Z"), ZoneId.of("UTC"));
    }
}