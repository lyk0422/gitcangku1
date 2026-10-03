package com.example.starter.calibration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间源配置；业务统一使用 UTC 时钟，测试可替换为可控时钟。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemUTC();
    }
}