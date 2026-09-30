package com.example.starter.translation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 业务时钟配置：默认使用 UTC 系统时钟；测试可替换为固定时钟以验证签署/发布时间固化。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
