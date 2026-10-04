package com.example.starter.plan;


import java.time.Instant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可控时钟替换系统时钟，默认固定在 2026-09-01T00:00:00Z
 * （早于全部测试计划时刻，即默认所有计划均未开始）。
 */
@Configuration
public class TestClockConfig {

    /** 默认测试时刻。 */
    public static final Instant DEFAULT_INSTANT = Instant.parse("2026-09-01T00:00:00Z");

    /**
     * 可控时钟，优先级高于 TimeConfig 中的系统时钟。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(DEFAULT_INSTANT);
    }

}
