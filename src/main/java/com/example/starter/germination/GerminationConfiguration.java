package com.example.starter.germination;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 发芽率模块 Bean 配置；时间统一经 {@link Clock} 获取，测试可替换为固定时钟。
 */
@Configuration
public class GerminationConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
