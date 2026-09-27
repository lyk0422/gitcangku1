package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务"当前时刻"统一取自该 Clock，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产默认使用系统 UTC 时钟。
     */
    @Bean
    public Clock operationClock() {
        return Clock.systemUTC();
    }
}
