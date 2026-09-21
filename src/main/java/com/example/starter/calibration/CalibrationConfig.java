package com.example.starter.calibration;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务通用 Bean 配置。
 */
@Configuration
public class CalibrationConfig {

    /**
     * 统一使用 UTC 时钟，便于测试替换与重启后语义一致。
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
