package com.example.starter.consent;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间配置：委托有效期按 UTC 判定，时钟可替换以便测试控制当前时间。
 */
@Configuration
public class TimeConfig {

    /**
     * 系统 UTC 时钟，测试中可覆盖为可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
