package com.example.starter.translation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时间口径配置：业务时间一律取 UTC 时钟。
 * 测试中可替换为固定/可控时钟验证区间边界，生产环境为系统 UTC 时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
