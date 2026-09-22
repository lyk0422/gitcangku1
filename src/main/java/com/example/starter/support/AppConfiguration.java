package com.example.starter.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用基础设施配置。
 */
@Configuration
public class AppConfiguration {

    /** 系统 UTC 时钟；测试可替换为固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
