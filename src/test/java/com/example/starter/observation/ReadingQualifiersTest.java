package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读数限定符工具的纯单元测试：规范化、旧数据默认值与整组等价判定。
 */
class ReadingQualifiersTest {

    @Test
    void normalizeDefaultsToExactAndAcceptsCaseInsensitiveValues() {
        assertThat(ReadingQualifiers.normalize(null)).isEqualTo("EXACT");
        assertThat(ReadingQualifiers.normalize("  ")).isEqualTo("EXACT");
        assertThat(ReadingQualifiers.normalize("EXACT")).isEqualTo("EXACT");
        assertThat(ReadingQualifiers.normalize("lt")).isEqualTo("LT");
        assertThat(ReadingQualifiers.normalize(" gT ")).isEqualTo("GT");
    }

    @Test
    void normalizeRejectsUnknownQualifier() {
        assertThat(ReadingQualifiers.normalize("AROUND")).isNull();
        assertThat(ReadingQualifiers.normalize("LTE")).isNull();
        assertThat(ReadingQualifiers.normalize("")).isEqualTo("EXACT");
    }

    @Test
    void orDefaultMapsNullToExactWithoutRewriting() {
        assertThat(ReadingQualifiers.orDefault(null)).isEqualTo("EXACT");
        assertThat(ReadingQualifiers.orDefault("LT")).isEqualTo("LT");
    }

    @Test
    void groupEqualsRequiresSameQualifierAndEqualValue() {
        // 5.0 与 5.000 数值相等、限定符相同：等价
        assertThat(ReadingQualifiers.groupEquals("EXACT", "5.0", "EXACT", "5.000")).isTrue();
        assertThat(ReadingQualifiers.groupEquals("LT", "5", "LT", "5.00")).isTrue();
        // EXACT 5 与 LT 5 限定符不同：不等价
        assertThat(ReadingQualifiers.groupEquals("EXACT", "5", "LT", "5")).isFalse();
        assertThat(ReadingQualifiers.groupEquals("GT", "5", "GT", "6")).isFalse();
        // 墓碑（null 值）仅与 null 组等价
        assertThat(ReadingQualifiers.groupEquals(null, null, null, null)).isTrue();
        assertThat(ReadingQualifiers.groupEquals(null, null, "EXACT", "5")).isFalse();
    }

    @Test
    void valueEqualsComparesNumerically() {
        assertThat(ReadingQualifiers.valueEquals("5", "5.000")).isTrue();
        assertThat(ReadingQualifiers.valueEquals("5", "6")).isFalse();
        assertThat(ReadingQualifiers.valueEquals(null, null)).isTrue();
        assertThat(ReadingQualifiers.valueEquals(null, "5")).isFalse();
    }
}