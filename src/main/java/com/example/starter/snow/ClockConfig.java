package com.example.starter.snow;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供可替换的 UTC 时钟，测试中可注入可控时钟以固定三小时窗口。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产环境使用系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}