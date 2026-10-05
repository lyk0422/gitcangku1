package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一时间口径：业务时间判定（如计划是否已开始运行）经 Clock 获取，测试可替换为受控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟，生产默认实现。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
