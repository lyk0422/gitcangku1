package com.example.starter.baggage;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时钟配置：短卸登记与补到时刻使用 UTC 系统时钟；
 * 测试可注入固定/偏移 Clock 实现可控时间断言。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
