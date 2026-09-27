package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 可注入时钟：生产使用 UTC 系统时钟；测试可替换为可控时钟以验证效价衰减的时间边界。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
