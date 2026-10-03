package com.example.starter;

import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** 测试时钟配置：以可控时钟替换业务时钟。 */
@TestConfiguration
public class TestClockConfig {

    /** 可控时钟单例，测试通过该实例推进时刻。 */
    public static final MutableClock MUTABLE_CLOCK =
            new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Bean
    @Primary
    public Clock testBusinessClock() {
        return MUTABLE_CLOCK;
    }
}