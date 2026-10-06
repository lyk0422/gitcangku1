package com.example.starter.translation.api;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * JSON 输出双向控制符转义配置：U+202A~U+202E 与 U+2066~U+2069 序列化为 \\uXXXX 转义形式。
 * 仅改变 JSON 展示层，解析后码点串与原文完全一致，不改变文本本身。
 */
@Component
public class BidiControlEscapeConfig {

    public BidiControlEscapeConfig(ObjectMapper objectMapper) {
        objectMapper.getFactory().setCharacterEscapes(new BidiControlEscapes());
    }

    /** 双向控制符转义表：ASCII 控制符沿用 JSON 标准转义，双向控制符按 \\uXXXX 输出。 */
    private static final class BidiControlEscapes extends CharacterEscapes {

        private final int[] asciiEscapes = CharacterEscapes.standardAsciiEscapesForJSON();

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            if ((ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)) {
                return new SerializedString("\\u" + String.format("%04x", ch));
            }
            return null;
        }
    }
}
