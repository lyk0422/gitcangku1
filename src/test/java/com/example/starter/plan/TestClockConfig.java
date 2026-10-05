package com.example.starter.plan;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以固定时刻（运营日前）的可控时钟替换系统时钟，
 * 用例可通过 MutableClock 推进时间。默认 2026-09-20T00:00:00Z，早于测试运营日。
 */
@TestConfiguration
public class TestClockConfig {

    /** 默认测试时刻：2026-09-22 运营日之前。 */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(DEFAULT_NOW);
    }
}
