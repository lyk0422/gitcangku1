package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 应用基础配置：提供可替换的时钟，测试中以可控时钟覆盖。
 */
@Configuration
public class AppConfig {

    /**
     * 业务时间统一使用的时钟（UTC）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}