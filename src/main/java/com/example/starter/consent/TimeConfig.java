package com.example.starter.consent;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间配置：提供可注入的 UTC 时钟，冻结到期判定与时间戳均通过该时钟获取，
 * 测试中可替换为可控时钟以确定性验证到期语义。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
