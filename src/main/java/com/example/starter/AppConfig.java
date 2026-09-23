package com.example.starter;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用基础配置：提供系统 UTC 时钟；时间相关测试可注入固定/偏移时钟。
 */
@Configuration
public class AppConfig {

    /**
     * 统一使用 UTC 时钟判定迁移生效窗口与查询代次签发时刻。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
