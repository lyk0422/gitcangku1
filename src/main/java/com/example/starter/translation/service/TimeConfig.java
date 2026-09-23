package com.example.starter.translation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间相关 Bean：默认使用 UTC 系统时钟；测试可注入固定时钟验证退役窗口边界。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
