package com.example.starter.plan.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务时钟配置：站台长度下调回查“未来已发布计划”时以该时钟判定运营日边界
 * （Asia/Shanghai 日历日）；测试可替换为固定时钟保证确定性。
 */
@Configuration
public class BusinessClockConfig {

    /**
     * 业务时钟，默认系统时钟（Asia/Shanghai 时区解释“今天”）。
     */
    @Bean
    public Clock businessClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
