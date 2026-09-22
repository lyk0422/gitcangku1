package com.example.starter.maintenance.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用配置：提供可替换的 UTC 时钟，便于时间相关测试使用可控时钟。
 */
@Configuration
public class MaintenanceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
