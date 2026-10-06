package com.example.starter.translation.config;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双向控制符 JSON 转义单元测试：控制符以转义形式展示，标准转义行为不变，解析后文本不变。
 */
class BidiCharacterEscapesTest {

    private static final String RLI = Character.toString(0x2067);
    private static final String PDI = Character.toString(0x2069);

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder().build()
            .setCharacterEscapes(new BidiCharacterEscapes()));

    @Test
    @DisplayName("隔离符序列化为转义形式，解析后码点不变")
    void isolatesEscapedAndRoundTrip() throws Exception {
        String text = "A" + RLI + "B" + PDI;
        String json = mapper.writeValueAsString(text);
        assertThat(json).isEqualTo("\"A\\u2067B\\u2069\"");
        assertThat(mapper.readValue(json, String.class)).isEqualTo(text);
    }

    @Test
    @DisplayName("U+202A~U+202E 同样转义展示")
    void embeddingControlsEscaped() throws Exception {
        for (int cp = 0x202A; cp <= 0x202E; cp++) {
            String json = mapper.writeValueAsString(new String(Character.toChars(cp)));
            assertThat(json).isEqualTo("\"\\u" + String.format("%04X", cp) + "\"");
        }
    }

    @Test
    @DisplayName("标准转义（换行、引号、反斜杠）与普通非 ASCII 字符行为不变")
    void standardEscapesUnchanged() throws Exception {
        assertThat(mapper.writeValueAsString("a\nb\"c\\d")).isEqualTo("\"a\\nb\\\"c\\\\d\"");
        assertThat(mapper.writeValueAsString("你好")).isEqualTo("\"你好\"");
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(mapper.writeValueAsString(emoji)).isEqualTo("\"" + emoji + "\"");
    }
}
