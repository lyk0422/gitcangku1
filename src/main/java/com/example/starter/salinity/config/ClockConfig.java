package com.example.starter.salinity.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间来源配置；测试可替换为固定或可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 业务时间统一使用 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
