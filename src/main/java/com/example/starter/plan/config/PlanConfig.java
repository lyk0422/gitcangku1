package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 计划域基础配置。
 */
@Configuration
public class PlanConfig {

    /**
     * 业务时钟（UTC），用于"计划是否已开始运行"等时间裁决，测试可替换为可控时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
