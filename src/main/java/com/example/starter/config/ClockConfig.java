package com.example.starter.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用基础配置：统一注入 UTC 时钟，业务时间一律以该时钟为准；
 * 测试可提供自定义 Clock Bean（如固定/可变时钟）替换，避免依赖真实墙钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统 UTC 时钟：Instant.now(clock) 即统一时间口径；测试上下文已提供 Clock 时不创建。
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
