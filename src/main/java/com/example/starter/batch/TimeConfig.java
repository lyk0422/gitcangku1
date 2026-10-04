package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 统一时钟：全系统时间一律 UTC instant；测试可替换为可控时钟以覆盖区间端点。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产时钟：系统 UTC。
     */
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}