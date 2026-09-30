package com.example.starter.water;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：生产环境使用系统 UTC 时钟；测试可提供固定/可变 Clock bean 替换，
 * 以便确定性地验证“排班未开始/已开始”等时间分支。
 */
@Configuration
public class WaterClockConfig {

    @Bean
    public Clock waterClock() {
        return Clock.systemUTC();
    }
}
