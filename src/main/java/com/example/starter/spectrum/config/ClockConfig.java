package com.example.starter.spectrum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用基础设施配置：时间使用 UTC 时钟，测试中可替换为固定时钟。
 */
@Configuration
public class ClockConfig {

    /** 业务落库时间统一取 UTC 毫秒；测试可用固定 Clock 替换该 bean。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
