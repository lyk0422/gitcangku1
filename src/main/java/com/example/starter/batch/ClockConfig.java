package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 服务端时钟配置：有效期到期判定与全部写时间均取自该时钟，测试可注入可控时钟替换。
 */
@Configuration
public class ClockConfig {

    /**
     * 生产默认使用系统 UTC 时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
