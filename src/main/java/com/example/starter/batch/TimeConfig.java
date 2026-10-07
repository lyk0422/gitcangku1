package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：全系统统一使用 UTC 时钟；测试可替换为可控时钟以覆盖时间边界。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
