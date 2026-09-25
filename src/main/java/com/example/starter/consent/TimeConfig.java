package com.example.starter.consent;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间配置：全系统统一使用 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    /**
     * 业务时钟：证明提交、到期比较、快照与审计时间均以此时钟的 UTC 瞬时为准。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
