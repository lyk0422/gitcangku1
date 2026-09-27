package com.example.starter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源配置：业务时间统一通过可注入的 UTC Clock 获取，
 * 测试可替换为可控时钟以验证效价衰减与放行时点核验。
 */
@Configuration
public class TimeConfiguration {

    /**
     * 生产环境使用系统 UTC 时钟。
     */
    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}
