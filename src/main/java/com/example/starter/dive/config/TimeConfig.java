package com.example.starter.dive.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源配置：业务时间统一走可替换的Clock，测试可注入固定时钟。
 */
@Configuration
public class TimeConfig {

    /** 系统UTC时钟。 */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}