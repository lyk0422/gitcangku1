package com.example.starter.cue;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 提供可控时钟，测试中可替换为固定时钟以校验时间字段。
 */
@Configuration
public class CueClockConfig {

    @Bean
    public Clock cueClock() {
        return Clock.systemDefaultZone();
    }
}