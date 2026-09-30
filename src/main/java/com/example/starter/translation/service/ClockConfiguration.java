package com.example.starter.translation.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时钟配置：固定使用 Asia/Shanghai 时区，签署等时间点由该时钟生成，测试可替换为固定时钟。
 */
@Configuration
public class ClockConfiguration {

    /** 业务时区：签署时间与 JSON 输出统一使用的时区。 */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
