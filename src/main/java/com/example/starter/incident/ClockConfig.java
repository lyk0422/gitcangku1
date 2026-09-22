package com.example.starter.incident;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：默认使用系统 UTC 时钟；测试可注入固定/偏移 Clock 控制当前时刻。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
