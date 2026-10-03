package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 时间源配置：业务统一使用UTC时钟，测试中可替换为可控时钟。 */
@Configuration
public class TimeConfig {

    /** 业务时钟，完成时刻、截止判定与确认时刻均取自此时钟（UTC）。 */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}