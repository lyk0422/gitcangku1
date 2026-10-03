package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务统一通过 Clock 取 UTC 时刻，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfiguration {

    /**
     * 生产环境使用系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}