package com.example.starter.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务 Bean 配置；统一使用 Asia/Shanghai 时钟，测试可替换为固定时钟。
 */
@Configuration
public class AppConfig {

    /** 业务时间统一时区。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 系统时钟，生产使用上海时区当前时间；测试中可注入可控时钟。
     */
    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
