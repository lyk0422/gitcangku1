package com.example.starter.plan;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：默认系统 UTC 时钟；测试可替换为可控时钟以覆盖"已开始计划"判定。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务时钟（UTC）。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
