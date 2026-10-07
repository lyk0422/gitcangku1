package com.example.starter.support;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
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

    /**
     * 禁止浮点标量强转为整型：sizeBytes/budgetBytes 等声明为整数，
     * 传入 1.5 等非整数时反序列化直接失败并映射为 400，而不是静默截断。
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer rejectFloatingPointToInteger() {
        return builder -> builder.postConfigurer(objectMapper ->
                objectMapper.coercionConfigFor(LogicalType.Integer)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail));
    }
}
