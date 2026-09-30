package com.example.starter.calibration;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.starter.calibration.service.BusinessClock;

/**
 * 测试时钟配置：以可控固定时钟替换系统时钟，初始固定在 2026-06-01T00:00:00Z。
 */
@TestConfiguration
public class FixedClockTestConfig {

    /** 测试默认“当前时刻”：2026-06-01T00:00:00Z。 */
    public static final Instant INITIAL_TIME = Instant.parse("2026-06-01T00:00:00Z");

    @Bean
    @Primary
    public ControllableBusinessClock controllableClock() {
        return new ControllableBusinessClock(INITIAL_TIME);
    }
}
