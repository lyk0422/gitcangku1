package com.example.starter.translation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引文锚点无状态规则单元测试：引用标识规范化、Unicode 码点区间与交叉判定。
 */
class CitationRulesTest {

    @Test
    @DisplayName("规范化引用标识：NFKC、去全部空白、根区域小写")
    void normalizeKey() {
        assertThat(CitationRules.normalizeKey("ISBN 978-4")).isEqualTo("isbn978-4");
        assertThat(CitationRules.normalizeKey(" isbn978-4 ")).isEqualTo("isbn978-4");
        assertThat(CitationRules.normalizeKey("ISBN\t978-4\n")).isEqualTo("isbn978-4");
        // NFKC：全角数字与字母折叠为半角
        assertThat(CitationRules.normalizeKey("ＩＳＢＮ９７８")).isEqualTo("isbn978");
        // 连字符不属于空白，原样保留
        assertThat(CitationRules.normalizeKey("DOI-10.1")).isEqualTo("doi-10.1");
    }

    @Test
    @DisplayName("码点长度与区间截取：代理对字符按一个码点计，左闭右开")
    void codePointSlice() {
        String text = "a𝕏b";
        assertThat(CitationRules.codePointLength(text)).isEqualTo(3);
        assertThat(CitationRules.slice(text, 0, 1)).isEqualTo("a");
        assertThat(CitationRules.slice(text, 1, 2)).isEqualTo("𝕏");
        assertThat(CitationRules.slice(text, 1, 3)).isEqualTo("𝕏b");
        // 中文同样按码点
        assertThat(CitationRules.slice("机器学习", 2, 4)).isEqualTo("学习");
    }

    @Test
    @DisplayName("区间合法性：空区间、倒挂、越界均不合法，取到末尾合法")
    void validRange() {
        assertThat(CitationRules.isValidRange(0, 2, 3)).isTrue();
        assertThat(CitationRules.isValidRange(0, 3, 3)).isTrue();
        assertThat(CitationRules.isValidRange(0, 0, 3)).isFalse();
        assertThat(CitationRules.isValidRange(2, 1, 3)).isFalse();
        assertThat(CitationRules.isValidRange(0, 4, 3)).isFalse();
        assertThat(CitationRules.isValidRange(-1, 2, 3)).isFalse();
    }

    @Test
    @DisplayName("区间交叉：端点相接不交叉，重叠才交叉")
    void crossing() {
        assertThat(CitationRules.crosses(0, 2, 2, 4)).isFalse();
        assertThat(CitationRules.crosses(0, 3, 2, 4)).isTrue();
        assertThat(CitationRules.crosses(2, 4, 0, 3)).isTrue();
        assertThat(CitationRules.crosses(0, 4, 1, 2)).isTrue();
        assertThat(CitationRules.crosses(0, 2, 3, 5)).isFalse();
    }
}
