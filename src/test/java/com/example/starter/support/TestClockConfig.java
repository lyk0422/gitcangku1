package com.example.starter.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试时钟配置：以可变时钟覆盖生产 Clock Bean，供用例精确控制业务时刻。
 */
@TestConfiguration
public class TestClockConfig {

    /** 用例统一基准时刻，@BeforeEach 会重置回该值。 */
    public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}