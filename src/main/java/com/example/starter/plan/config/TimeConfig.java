package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 系统时钟配置：生产使用 UTC 系统时钟；测试以可控时钟替换，保证时间相关判定可复现。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务判定统一使用的时钟（UTC）。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}