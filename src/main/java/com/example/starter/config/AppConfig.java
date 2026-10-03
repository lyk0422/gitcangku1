package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置：提供 UTC 时钟，便于测试替换为可控时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 业务使用的 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}