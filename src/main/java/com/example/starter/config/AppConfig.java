package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用基础配置。 */
@Configuration
public class AppConfig {

    /** 业务时钟，统一使用 UTC；测试中可替换为可控时钟。 */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}