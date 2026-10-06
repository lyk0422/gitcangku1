package com.example.starter.translation.config;

import com.fasterxml.jackson.core.JsonFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置：接口 JSON 输出对双向控制符使用反斜杠 u 加四位十六进制的转义展示，不改变文本内容。
 */
@Configuration
public class JacksonConfig {

    /** 为主 ObjectMapper 配置双向控制符转义的 JsonFactory。 */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bidiControlCharacterEscapes() {
        JsonFactory factory = JsonFactory.builder().build()
                .setCharacterEscapes(new BidiCharacterEscapes());
        return builder -> builder.factory(factory);
    }
}
