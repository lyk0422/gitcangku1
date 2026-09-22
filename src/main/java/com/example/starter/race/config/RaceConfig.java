package com.example.starter.race.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 领域通用 Bean：系统 UTC 时钟；测试可替换为固定时钟。
 */
@Configuration
public class RaceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
