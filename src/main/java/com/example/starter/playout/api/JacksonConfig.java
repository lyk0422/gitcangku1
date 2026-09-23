package com.example.starter.playout.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * 时间序列化约定：统一输出 Asia/Shanghai 偏移、毫秒精度的 ISO 8601（如 2026-09-21T10:00:00.000+08:00）；
 * 反序列化接受任意合法 ISO 8601 偏移时间。
 */
@Configuration
public class JacksonConfig {

    /** 毫秒精度、带时区偏移的输出格式。 */
    public static final DateTimeFormatter OUTPUT_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
            .appendOffset("+HH:MM", "+08:00")
            .toFormatter();

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer playoutJacksonCustomizer() {
        SimpleModule module = new SimpleModule("playout-time");
        module.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(OUTPUT_FORMAT.format(value));
            }
        });
        module.addDeserializer(OffsetDateTime.class, new JsonDeserializer<>() {
            @Override
            public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                return OffsetDateTime.parse(parser.getText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            }
        });
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDate value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE.format(value));
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override
            public LocalDate deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                return LocalDate.parse(parser.getText(), DateTimeFormatter.ISO_LOCAL_DATE);
            }
        });
        return builder -> builder
                .modules(module)
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .featuresToDisable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }
}
