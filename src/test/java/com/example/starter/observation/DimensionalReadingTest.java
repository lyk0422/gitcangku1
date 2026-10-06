package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 带量纲读数解析与规范化单元测试：单位归属、精确换算（不舍入）与 400 分支。
 */
class DimensionalReadingTest {

    @Test
    void centimetersNormalizeToMetersExactly() {
        DimensionalReading reading = DimensionalReading.parse("LENGTH", "cm", "100");
        assertThat(reading.dimension()).isEqualTo(Dimension.LENGTH);
        assertThat(reading.unit()).isEqualTo(ReadingUnit.CENTIMETER);
        assertThat(reading.value()).isEqualTo("100");
        assertThat(reading.normalizedValue()).isEqualTo("1");
    }

    @Test
    void normalizationKeepsMoreThanThreeFractionDigits() {
        // 1.234 cm = 0.01234 m：规范值精确保留，比较不得舍入到三位小数
        DimensionalReading reading = DimensionalReading.parse("LENGTH", "cm", "1.234");
        assertThat(reading.normalizedValue()).isEqualTo("0.01234");
    }

    @Test
    void kelvinNormalizesToCelsiusExactly() {
        DimensionalReading freezing = DimensionalReading.parse("TEMPERATURE", "K", "273.15");
        assertThat(freezing.normalizedValue()).isEqualTo("0");
        DimensionalReading warm = DimensionalReading.parse("TEMPERATURE", "K", "300");
        assertThat(warm.normalizedValue()).isEqualTo("26.85");
    }

    @Test
    void canonicalUnitsKeepOriginalValue() {
        assertThat(DimensionalReading.parse("LENGTH", "m", "1.5").normalizedValue()).isEqualTo("1.5");
        assertThat(DimensionalReading.parse("TEMPERATURE", "C", "-40").normalizedValue()).isEqualTo("-40");
    }

    @Test
    void missingDimensionAndUnitMeansLegacyUnitlessReading() {
        assertThat(DimensionalReading.parse(null, null, "1.0")).isNull();
    }

    @Test
    void dimensionWithoutUnitOrReverseIsRejected() {
        assertThatThrownBy(() -> DimensionalReading.parse("LENGTH", null, "1.0"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatThrownBy(() -> DimensionalReading.parse(null, "m", "1.0"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
    }

    @Test
    void unsupportedDimensionOrUnitIsRejected() {
        assertThatThrownBy(() -> DimensionalReading.parse("MASS", "kg", "1.0"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatThrownBy(() -> DimensionalReading.parse("LENGTH", "mm", "1.0"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatThrownBy(() -> DimensionalReading.parse("TEMPERATURE", "F", "1.0"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
    }

    @Test
    void unitOutsideDimensionIsRejected() {
        // 温度单位不能用于长度量纲，反之亦然
        assertThatThrownBy(() -> DimensionalReading.parse("LENGTH", "C", "1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatThrownBy(() -> DimensionalReading.parse("TEMPERATURE", "cm", "1"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
    }

    @Test
    void valueWithMoreThanThreeFractionDigitsIsRejected() {
        assertThatThrownBy(() -> DimensionalReading.parse("LENGTH", "m", "1.2345"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
        assertThatThrownBy(() -> DimensionalReading.parse("LENGTH", "m", "abc"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status().value()).isEqualTo(400);
    }

    @Test
    void dimensionNameIsCaseInsensitive() {
        DimensionalReading reading = DimensionalReading.parse("length", "m", "2");
        assertThat(reading.dimension()).isEqualTo(Dimension.LENGTH);
    }
}
