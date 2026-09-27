package com.example.starter.translation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间配置：业务时间统一取 UTC 时钟，测试中可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /** 系统 UTC 时钟；所有业务时间（冲突区间判定、登记/解除/排除时间）均取自该时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
