package com.example.starter.consent;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：驻留、迁移、清除与快照时间统一按 UTC 处理，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务时钟，默认 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
