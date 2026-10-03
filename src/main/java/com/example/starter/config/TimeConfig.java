package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务时间统一为 UTC，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产环境使用系统 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}