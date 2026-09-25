package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：到期判定统一使用可注入的 {@link Clock}，默认系统 UTC 时钟；
 * 测试可注入固定/偏移时钟，到期降级不依赖后台任务。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
