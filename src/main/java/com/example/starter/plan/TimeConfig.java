package com.example.starter.plan;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置：业务时间统一走 Clock（UTC），测试中可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 生产时钟：系统 UTC。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
