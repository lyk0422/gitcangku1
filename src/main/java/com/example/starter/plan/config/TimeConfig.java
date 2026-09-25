package com.example.starter.plan.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用基础设施配置：提供 UTC 系统时钟，业务服务统一依赖该时钟取当前时刻，
 * 测试可替换为固定时钟以验证到期边界与“未来计划”回查。
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock applicationClock() {
        return Clock.systemUTC();
    }
}
