package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用 Bean 配置；时钟使用 UTC，测试可替换为固定时钟。
 */
@Configuration
public class ServiceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
