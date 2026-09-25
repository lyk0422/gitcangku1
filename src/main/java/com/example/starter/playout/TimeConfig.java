package com.example.starter.playout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 业务时钟配置：生产环境使用系统时钟，测试可替换为可控时钟以验证时间相关分支。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemDefaultZone();
    }
}
