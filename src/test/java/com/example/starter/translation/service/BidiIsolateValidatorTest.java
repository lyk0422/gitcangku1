package com.example.starter.translation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双向隔离符校验器单元测试：按码点核验，覆盖合法、嵌套、禁用控制符、孤立结束符与未闭合边界。
 * 控制符通过码点常量构造，避免源文件中出现不可见字符。
 */
class BidiIsolateValidatorTest {

    private static final String LRI = Character.toString(0x2066);
    private static final String RLI = Character.toString(0x2067);
    private static final String FSI = Character.toString(0x2068);
    private static final String PDI = Character.toString(0x2069);

    @Test
    @DisplayName("普通文本与题干样例 A+U+2067+B+U+2069 合法")
    void plainAndSimpleIsolateValid() {
        assertThat(BidiIsolateValidator.findErrorIndex("你好世界")).isEqualTo(-1);
        assertThat(BidiIsolateValidator.findErrorIndex("")).isEqualTo(-1);
        assertThat(BidiIsolateValidator.findErrorIndex("A" + RLI + "B" + PDI)).isEqualTo(-1);
    }

    @Test
    @DisplayName("三种隔离起始均合法，嵌套 4 层合法")
    void nestedUpToFourValid() {
        assertThat(BidiIsolateValidator.findErrorIndex(LRI + "a" + PDI)).isEqualTo(-1);
        assertThat(BidiIsolateValidator.findErrorIndex(FSI + "a" + PDI)).isEqualTo(-1);
        assertThat(BidiIsolateValidator.findErrorIndex(
                LRI + RLI + FSI + LRI + "x" + PDI + PDI + PDI + PDI)).isEqualTo(-1);
    }

    @Test
    @DisplayName("第 5 层嵌套超限，报告该起始符的码点索引")
    void fifthLevelRejected() {
        // 4 层合法起始后，第 5 个起始符位于码点索引 4
        assertThat(BidiIsolateValidator.findErrorIndex(LRI + LRI + LRI + LRI + LRI)).isEqualTo(4);
        assertThat(BidiIsolateValidator.findErrorIndex("ab" + LRI + LRI + LRI + LRI + LRI)).isEqualTo(6);
    }

    @Test
    @DisplayName("孤立结束符 U+2069 拒绝并报告其码点索引")
    void orphanPdiRejected() {
        assertThat(BidiIsolateValidator.findErrorIndex(PDI)).isEqualTo(0);
        assertThat(BidiIsolateValidator.findErrorIndex("AB" + PDI)).isEqualTo(2);
        // 配对后再出现多余结束符同样拒绝
        assertThat(BidiIsolateValidator.findErrorIndex(LRI + "a" + PDI + PDI)).isEqualTo(3);
    }

    @Test
    @DisplayName("U+202A~U+202E 嵌入/覆盖控制符全部拒绝并报告索引")
    void embeddingControlsRejected() {
        for (int cp = 0x202A; cp <= 0x202E; cp++) {
            String text = "xy" + new String(Character.toChars(cp));
            assertThat(BidiIsolateValidator.findErrorIndex(text)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("结束未闭合：报告最早未闭合起始符的码点索引")
    void unclosedIsolateReportsEarliestStart() {
        assertThat(BidiIsolateValidator.findErrorIndex("AB" + LRI + "CD")).isEqualTo(2);
        assertThat(BidiIsolateValidator.findErrorIndex(LRI + "a" + LRI + "b" + PDI)).isEqualTo(0);
    }

    @Test
    @DisplayName("补充平面字符计为一个码点：表情后孤立 U+2069 报告索引 1")
    void supplementaryPlaneCountsAsOneCodePoint() {
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(BidiIsolateValidator.findErrorIndex(emoji + PDI)).isEqualTo(1);
        // 表情参与嵌套计数也按码点：表情 + 合法隔离对
        assertThat(BidiIsolateValidator.findErrorIndex(emoji + RLI + "B" + PDI)).isEqualTo(-1);
    }

    @Test
    @DisplayName("其他控制符（如 U+200E/U+200F 方向标记）按原文接受")
    void otherMarksAccepted() {
        String lrm = Character.toString(0x200E);
        String rlm = Character.toString(0x200F);
        assertThat(BidiIsolateValidator.findErrorIndex("a" + lrm + "b" + rlm + "c")).isEqualTo(-1);
    }
}
