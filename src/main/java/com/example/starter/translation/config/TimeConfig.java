package com.example.starter.translation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间来源配置：统一使用注入的 Clock，便于测试使用可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟；所有持久化时间均为 UTC 瞬时。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
