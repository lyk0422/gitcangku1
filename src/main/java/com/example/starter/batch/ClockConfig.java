package com.example.starter.batch;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟配置：暴露可注入的系统 UTC 时钟，服务端当前时刻统一经该时钟获取；
 * 测试可注入可控时钟（固定或可拨动）验证到期判定，无需真实等待。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
