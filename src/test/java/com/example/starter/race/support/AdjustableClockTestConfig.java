package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 接力测试用可调时钟配置：替换系统时钟，交接提交间可推进服务端时刻。
 */
@TestConfiguration
public class AdjustableClockTestConfig {

    public static final Instant START = Instant.parse("2026-09-25T04:00:00Z");

    @Bean
    @Primary
    public AdjustableClock adjustableClock() {
        return new AdjustableClock(START);
    }
}
