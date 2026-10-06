package com.example.starter.translation.config;

import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharTypes;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.io.SerializedString;

/**
 * JSON 字符转义：将双向控制符（U+202A~U+202E、U+2066~U+2069）序列化为反斜杠 u 加四位十六进制形式。
 * ASCII 范围沿用 Jackson 标准转义表（引号、反斜杠、控制字符行为不变）；
 * 仅改变接口 JSON 的展示形式，解析后文本码点与原文完全一致，不改变文本本身。
 */
public class BidiCharacterEscapes extends CharacterEscapes {

    private final int[] asciiEscapes = CharTypes.get7BitOutputEscapes();

    @Override
    public int[] getEscapeCodesForAscii() {
        return asciiEscapes;
    }

    @Override
    public SerializableString getEscapeSequence(int ch) {
        if ((ch >= 0x202A && ch <= 0x202E) || (ch >= 0x2066 && ch <= 0x2069)) {
            return new SerializedString("\\u" + String.format("%04X", ch));
        }
        return null;
    }
}
