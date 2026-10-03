package com.example.starter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源配置：业务统一通过 Clock 取当前 UTC 时间，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /** 生产时钟：UTC。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}