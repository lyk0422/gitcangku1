package com.example.starter.restitution.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 时钟配置：业务统一按 Asia/Shanghai 解释；测试可注入固定 Clock 实现可控时间。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
