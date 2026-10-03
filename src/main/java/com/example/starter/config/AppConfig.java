package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 应用基础配置：提供可替换的时钟，测试用可控时钟覆盖。 */
@Configuration
public class AppConfig {

    /** 业务时间统一取 UTC 时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}