package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务代码统一通过注入的 Clock 获取 UTC 时刻，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产环境使用系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}