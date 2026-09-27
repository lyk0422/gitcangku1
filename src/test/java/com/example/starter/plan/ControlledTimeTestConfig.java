package com.example.starter.plan;

import com.example.starter.plan.service.TimeProvider;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 危险品相关集成测试的测试装配：以主时钟固定为指定 UTC 时刻的可控时钟替换系统墙钟。
 */
@TestConfiguration
public class ControlledTimeTestConfig {

    /** 危险品测试统一基准时刻（UTC）。 */
    public static final Instant BASE = Instant.parse("2026-09-22T12:00:00Z");

    @Bean
    @Primary
    public TimeProvider controlledTimeProvider() {
        return new ControlledTimeProvider(BASE);
    }
}
