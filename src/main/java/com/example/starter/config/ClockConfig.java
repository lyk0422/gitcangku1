package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务默认时刻（登记、交接发起/确认等）统一走 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 系统 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}