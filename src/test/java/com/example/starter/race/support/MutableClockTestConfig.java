package com.example.starter.race.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 可推进时钟测试配置：初始时刻与 {@link FixedClockTestConfig} 一致，
 * 测试可注入 {@link MutableClock} 推进时间以覆盖申诉窗口边界。
 */
@TestConfiguration
public class MutableClockTestConfig {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(FixedClockTestConfig.FIXED_INSTANT);
    }
}
