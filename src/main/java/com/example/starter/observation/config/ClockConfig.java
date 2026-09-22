package com.example.starter.observation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 统一时钟，业务固定使用 Asia/Shanghai 时区，测试可替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
