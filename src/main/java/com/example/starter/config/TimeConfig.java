package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：全系统统一使用 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务时钟，所有窗口与 72 小时确认期限判断均基于此时钟（UTC）。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}