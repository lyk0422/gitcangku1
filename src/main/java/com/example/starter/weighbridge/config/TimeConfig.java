package com.example.starter.weighbridge.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：业务时间统一走 Clock，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产时钟，使用系统默认时区（Asia/Shanghai 由 JVM/容器时区决定）。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemDefaultZone();
    }
}