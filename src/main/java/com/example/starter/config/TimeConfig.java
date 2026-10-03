package com.example.starter.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务时钟配置：默认使用 UTC 系统时钟，测试中可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 提供 UTC 时钟，所有业务时间判断均通过该时钟读取。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}