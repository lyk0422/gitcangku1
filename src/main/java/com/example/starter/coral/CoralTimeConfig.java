package com.example.starter.coral;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供可替换的UTC时钟，测试中可注入可控时钟。
 */
@Configuration
public class CoralTimeConfig {

    @Bean
    public Clock coralClock() {
        return Clock.systemUTC();
    }
}