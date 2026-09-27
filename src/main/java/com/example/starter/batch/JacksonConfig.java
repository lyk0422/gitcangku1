package com.example.starter.batch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * Jackson 配置：效价、阈值、差额、数量、权重等 BigDecimal 字段以其自身刻度输出纯数字，
 * 不去掉尾零，保证响应中 6 位效价/数量、10 位权重的时间精度与 null 语义稳定，
 * 也保证成功重放的响应快照字节级一致。
 */
@Configuration
public class JacksonConfig {

    /**
     * 注册保留刻度的 BigDecimal 序列化模块；各字段在构造时已显式设定刻度。
     */
    @Bean
    public SimpleModule fixedScaleBigDecimalModule() {
        SimpleModule module = new SimpleModule("fixedScaleBigDecimal");
        module.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeNumber(value.toPlainString());
            }
        });
        return module;
    }
}
