package com.example.starter.service;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可替换的 UTC 时钟：运行期用系统 UTC，测试期替换为可控时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}