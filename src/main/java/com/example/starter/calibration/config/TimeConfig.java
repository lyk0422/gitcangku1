package com.example.starter.calibration.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：统一使用 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 应用统一时钟（UTC）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}