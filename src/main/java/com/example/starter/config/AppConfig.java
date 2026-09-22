package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 应用基础配置：统一注入时钟，便于测试使用可控时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 业务时钟，固定使用 Asia/Shanghai 时区。
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
