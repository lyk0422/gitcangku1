package com.example.starter;

import com.example.starter.support.BusinessClock;
import com.example.starter.support.FixedBusinessClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 测试配置：用可控固定时钟替换系统时钟，签发/作废/放行时刻可断言。
 */
@TestConfiguration
public class FixedClockTestConfiguration {

    @Bean
    @Primary
    public BusinessClock fixedBusinessClock() {
        return new FixedBusinessClock();
    }
}
