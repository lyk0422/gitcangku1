package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：生产使用 UTC 系统时钟，测试以可控时钟替换，保证时间相关断言可复现。
 */
@Configuration
public class TimeConfig {

    /**
     * 提供应用统一使用的 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}