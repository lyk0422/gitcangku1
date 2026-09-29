package com.example.starter.calibration.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用时钟配置：全系统统一 UTC 时钟；测试可注入受控 Clock 验证时间语义。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
