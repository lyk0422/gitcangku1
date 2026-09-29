package com.example.starter.translation.archive;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 归档域时间配置：统一使用 UTC 时钟；测试可注入固定/偏移时钟做确定性时间断言。
 */
@Configuration
public class ArchiveClockConfig {

    @Bean
    public Clock archiveClock() {
        return Clock.systemUTC();
    }
}
