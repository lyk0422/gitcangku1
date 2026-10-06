package com.example.starter.translation.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 幂等摘要用的十进制规范化序列化：按值输出（去尾零、非科学计数、零归一为 0），
 * 保证 1234.5 与 1234.50、1E+3 与 1000 的 requestId 摘要一致；文本字段不在此列，原样参与摘要。
 */
public final class CanonicalDecimalSerializer extends JsonSerializer<BigDecimal> {

    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value.signum() == 0) {
            gen.writeString("0");
            return;
        }
        gen.writeString(value.stripTrailingZeros().toPlainString());
    }
}
