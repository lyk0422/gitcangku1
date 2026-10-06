package com.example.starter.consent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 强制转换约束：数值记录的值必须是非负整数字面量，
 * 浮点（如 1.5）不得被静默截断为整数，统一按 400 处理。
 */
@Configuration
public class JacksonCoercionConfig {

    @Bean
    static BeanPostProcessor strictIntegerCoercionPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ObjectMapper mapper) {
                    mapper.coercionConfigFor(LogicalType.Integer)
                            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
                }
                return bean;
            }
        };
    }
}
