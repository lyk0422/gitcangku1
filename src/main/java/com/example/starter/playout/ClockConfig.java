package com.example.starter.playout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间来源配置：默认使用系统 UTC 时钟；测试可用固定/可控时钟替换，用于派生占位状态等时间相关结论。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
