package com.example.starter.support;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 通用基础设施配置。
 */
@Configuration
public class AppConfiguration {

    /** 系统 UTC 时钟；测试可替换为固定时钟。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 非整数 JSON 数值（如 10.5）不得截断为整型，按请求不合法（400）处理。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictIntegralCoercion() {
        return builder -> builder.featuresToDisable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
