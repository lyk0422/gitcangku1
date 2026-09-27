package com.example.starter.water;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间时钟配置：业务一律按 UTC 取当前时间。测试可提供自己的固定/可推进 Clock 替身
 * （{@code @Primary}），从而确定性验证“已开始小时不可修改曲线”等时间边界。
 */
@Configuration
public class WaterClockConfig {

    /** 业务统一时钟，固定为 UTC。 */
    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}
