package com.example.starter.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置。
 */
@Configuration
public class AppConfig {

    /**
     * 业务时钟（UTC）；测试中可替换为可控时钟以固定“最近 15 分钟”窗口。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}