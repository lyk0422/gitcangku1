package com.example.starter.water;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 服务端时钟配置。业务时间一律通过该 Clock 获取（UTC），测试可替换为可控时钟。
 */
@Configuration
public class WaterConfig {

    /** 默认使用系统 UTC 时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
