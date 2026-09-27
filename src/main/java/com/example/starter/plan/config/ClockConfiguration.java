package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务服务统一通过该 Clock 取 UTC 当前时刻，
 * 生产为系统 UTC 时钟，测试可替换为固定时钟以确定性断言时间精度。
 */
@Configuration
public class ClockConfiguration {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
