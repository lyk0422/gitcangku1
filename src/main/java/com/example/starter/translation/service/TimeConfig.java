package com.example.starter.translation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间口径配置：统一使用 UTC 系统时钟；测试可注入固定/可控时钟验证历史区间。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
