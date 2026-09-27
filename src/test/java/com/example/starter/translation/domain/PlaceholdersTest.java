package com.example.starter.translation.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版式占位符领域规则的纯单元测试：不启动 Spring，覆盖词法、规范排序、计数与非法格式分支。
 */
class PlaceholdersTest {

    @Test
    @DisplayName("声明规范化：去重并按 Unicode 字典序排序，空输入为空集合")
    void normalizeDeclarationSortsAndDeduplicates() {
        assertThat(Placeholders.normalizeDeclaration(null)).isEmpty();
        assertThat(Placeholders.normalizeDeclaration(List.of())).isEmpty();
        assertThat(Placeholders.normalizeDeclaration(List.of("{name}", "{0}", "{city}")))
                .containsExactly("{0}", "{city}", "{name}");
        assertThatThrownBy(() -> Placeholders.normalizeDeclaration(List.of("{a}", "{a}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    @DisplayName("词法：仅允许花括号包裹 1~62 个字母数字下划线；长度上限与空名称拒绝")
    void tokenLexicon() {
        assertThat(Placeholders.isLegalToken("{a}")).isTrue();
        assertThat(Placeholders.isLegalToken("{A_z9}")).isTrue();
        assertThat(Placeholders.isLegalToken("{" + "a".repeat(62) + "}")).isTrue();
        assertThat(Placeholders.isLegalToken("{" + "a".repeat(63) + "}")).isFalse();
        assertThat(Placeholders.isLegalToken("{}")).isFalse();
        assertThat(Placeholders.isLegalToken("{a b}")).isFalse();
        assertThat(Placeholders.isLegalToken("{a}}")).isFalse();
        assertThat(Placeholders.isLegalToken("a")).isFalse();
        assertThat(Placeholders.isLegalToken(null)).isFalse();
        assertThatThrownBy(() -> Placeholders.normalizeDeclaration(List.of("{a-b}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    @DisplayName("计数：非重叠扫描，结果按键字典序排列，重复出现累加")
    void countsAreSortedAndMultiplicityAware() {
        LinkedHashMap<String, Integer> counts = Placeholders.counts("x{name} y{0} {name}{name}{city}");
        assertThat(counts).containsExactlyEntriesOf(
                new LinkedHashMap<>() {{
                    put("{0}", 1);
                    put("{city}", 1);
                    put("{name}", 3);
                }});
        assertThat(Placeholders.expand(counts)).containsExactly("{0}", "{city}", "{name}", "{name}", "{name}");
        assertThat(Placeholders.counts("no tokens")).isEmpty();
        assertThat(Placeholders.counts(null)).isEmpty();
    }

    @Test
    @DisplayName("非法花括号：未闭合、空名称、含空格、嵌套花括号均判非法")
    void malformedBracesRejected() {
        assertThatThrownBy(() -> Placeholders.assertNoMalformedBraces("hello {name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未闭合");
        assertThatThrownBy(() -> Placeholders.assertNoMalformedBraces("{}"))
                .hasMessageContaining("格式非法");
        assertThatThrownBy(() -> Placeholders.assertNoMalformedBraces("{a b}"))
                .hasMessageContaining("格式非法");
        assertThatThrownBy(() -> Placeholders.assertNoMalformedBraces("{{a}}"))
                .hasMessageContaining("格式非法");
    }

    @Test
    @DisplayName("合法文本含多个占位符时不抛异常，普通文本无花括号也通过")
    void wellFormedTextPasses() {
        Placeholders.assertNoMalformedBraces("Hello {name}, you have {count} messages");
        Placeholders.assertNoMalformedBraces("普通文本不含花括号");
        Placeholders.assertNoMalformedBraces(null);
    }
}
