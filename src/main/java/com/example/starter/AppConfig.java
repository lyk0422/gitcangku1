package com.example.starter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用 Bean 配置；业务时间统一取 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
