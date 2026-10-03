package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：生产使用 UTC 系统时钟；测试可替换为可控时钟以覆盖"已开始计划"等时间边界。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务当前时刻来源（UTC）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}