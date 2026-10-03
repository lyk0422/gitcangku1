package com.example.starter.bridge;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 便桥调度通用配置；提供可替换的 UTC 时钟，测试中以可控时钟覆盖。
 */
@Configuration
public class BridgeConfig {

    /**
     * 业务时钟，统一使用 UTC；测试环境用可变时钟替换本 Bean。
     */
    @Bean
    public Clock bridgeClock() {
        return Clock.systemUTC();
    }
}