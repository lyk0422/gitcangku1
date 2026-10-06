package com.example.starter.translation;

import com.example.starter.translation.domain.BidiIsolateValidator;
import com.example.starter.translation.domain.BidiIsolateValidator.BidiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双向隔离符校验器单元测试：码点级栈校验，不依赖 Spring 上下文。
 * 隔离符按码点常量构造：LRI=U+2066、RLI=U+2067、FSI=U+2068、PDI=U+2069。
 */
class BidiIsolateValidatorTest {

    private static final String LRI = String.valueOf((char) 0x2066);
    private static final String RLI = String.valueOf((char) 0x2067);
    private static final String FSI = String.valueOf((char) 0x2068);
    private static final String PDI = String.valueOf((char) 0x2069);
    private static final String LRM = String.valueOf((char) 0x200E);
    private static final String RLM = String.valueOf((char) 0x200F);
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    @DisplayName("普通文本与不含控制符的文本合法")
    void plainTextValid() {
        assertThat(BidiIsolateValidator.validate("hello world")).isNull();
        assertThat(BidiIsolateValidator.validate("你好，世界")).isNull();
        assertThat(BidiIsolateValidator.validate("")).isNull();
    }

    @Test
    @DisplayName("A + U+2067 + B + U+2069 合法；三种起始符均合法")
    void isolatePairValid() {
        assertThat(BidiIsolateValidator.validate("A" + RLI + "B" + PDI)).isNull();
        assertThat(BidiIsolateValidator.validate("A" + LRI + "B" + PDI)).isNull();
        assertThat(BidiIsolateValidator.validate("A" + FSI + "B" + PDI)).isNull();
    }

    @Test
    @DisplayName("嵌套深度 4 合法，深度 5 在第 5 个起始符处报错")
    void nestingDepth() {
        String depth4 = LRI + RLI + FSI + LRI + "abcd" + PDI + PDI + PDI + PDI;
        assertThat(BidiIsolateValidator.validate(depth4)).isNull();

        String depth5 = LRI + RLI + FSI + LRI + RLI + "x" + PDI + PDI + PDI + PDI + PDI;
        BidiError error = BidiIsolateValidator.validate(depth5);
        assertThat(error).isNotNull();
        assertThat(error.codePointIndex()).isEqualTo(4);
        assertThat(error.reason()).contains("嵌套");
    }

    @Test
    @DisplayName("孤立 U+2069 报错并给出码点索引")
    void isolatedPdiRejected() {
        BidiError error = BidiIsolateValidator.validate("AB" + PDI);
        assertThat(error).isNotNull();
        assertThat(error.codePointIndex()).isEqualTo(2);
        assertThat(error.reason()).contains("孤立");
    }

    @Test
    @DisplayName("首字符为补充平面表情、第二码点为 U+2069 时报告索引 1（不按 UTF-16 位置）")
    void supplementaryPlaneIndex() {
        BidiError error = BidiIsolateValidator.validate(EMOJI + PDI);
        assertThat(error).isNotNull();
        assertThat(error.codePointIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("U+202A 至 U+202E 双向格式符全部禁止，索引按码点计")
    void embeddingControlsRejected() {
        for (int cp = 0x202A; cp <= 0x202E; cp++) {
            String text = "AB" + new String(Character.toChars(cp)) + "CD";
            BidiError error = BidiIsolateValidator.validate(text);
            assertThat(error).as("U+%04X 应被拒绝", cp).isNotNull();
            assertThat(error.codePointIndex()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("未闭合的隔离起始符在文本结束时报错，索引指向未闭合起始符")
    void unclosedIsolateRejected() {
        BidiError error = BidiIsolateValidator.validate("A" + LRI + "B");
        assertThat(error).isNotNull();
        assertThat(error.codePointIndex()).isEqualTo(1);
        assertThat(error.reason()).contains("未闭合");

        // 嵌套时内层闭合、外层未闭合，报告外层起始符索引
        BidiError nested = BidiIsolateValidator.validate(LRI + "A" + RLI + "B" + PDI);
        assertThat(nested).isNotNull();
        assertThat(nested.codePointIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("其他字符（含未禁止的控制符与补充平面字符）不影响校验，按原文保留")
    void otherCharactersIgnored() {
        // U+200E/U+200F 方向标记不在禁止范围
        assertThat(BidiIsolateValidator.validate("A" + LRM + "B" + RLM + "C")).isNull();
        // 补充平面表情计入码点但不影响结构
        assertThat(BidiIsolateValidator.validate(EMOJI + RLI + "x" + PDI)).isNull();
        // 配对错误：先弹后入仍按顺序校验
        BidiError error = BidiIsolateValidator.validate(PDI + LRI + PDI);
        assertThat(error).isNotNull();
        assertThat(error.codePointIndex()).isEqualTo(0);
    }
}
