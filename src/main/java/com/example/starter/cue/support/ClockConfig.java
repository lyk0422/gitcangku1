package com.example.starter.cue.support;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间与基础 Bean 配置；业务代码统一通过注入的 {@link Clock} 取当前时间，测试可替换为固定时钟。
 */
@Configuration
public class ClockConfig {

    /**
     * 业务时区固定为 Asia/Shanghai，时间列均按此时区记录本地时间。
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 系统时钟；测试中可用 {@link Clock#fixed} 覆盖。
     */
    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }

    /**
     * 按业务时区取当前本地时间。
     */
    public static LocalDateTime now(Clock clock) {
        return LocalDateTime.now(clock.withZone(BUSINESS_ZONE));
    }
}
