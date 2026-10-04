package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时间源。限速重排对"计划是否已开始运行"的判定全部走该时钟，
 * 测试可替换为可控时钟以覆盖时间边界；生产为系统 UTC 时钟。
 */
@Configuration
public class TimeConfiguration {

    /**
     * 应用统一时钟，默认系统 UTC。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}