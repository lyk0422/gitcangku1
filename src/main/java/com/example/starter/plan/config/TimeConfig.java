package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可注入时钟：实际进出时刻一律取自服务端 Clock，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产默认使用 UTC 系统时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
