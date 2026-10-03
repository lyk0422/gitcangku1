package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供 UTC 时钟，业务时间判断统一走该 Bean，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 默认使用系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}