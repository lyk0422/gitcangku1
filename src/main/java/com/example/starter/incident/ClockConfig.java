package com.example.starter.incident;

import java.time.Clock;
import java.time.ZoneOffset;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间时钟配置：默认使用系统 UTC 时钟；测试可注入固定/偏移 Clock 控制当前时刻，
 * 逾期判定不依赖静态 Instant.now()。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
