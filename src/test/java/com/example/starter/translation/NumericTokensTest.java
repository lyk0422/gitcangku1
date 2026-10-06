package com.example.starter.translation;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.NumericTokenRow;
import com.example.starter.translation.domain.Rows.TranslationTokenRow;
import com.example.starter.translation.service.NumericTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数值词元校核器纯单元测试：固定格式解析、源登记边界、提交侧完整校核与码点区间。
 */
class NumericTokensTest {

    private static final List<NumericTokenRow> SOURCE = List.of(
            new NumericTokenRow("s1", "depth", "1234.5", "米"));

    @Test
    @DisplayName("目标格式解析：合法格式按值解析，含负号、整数、最长 6 位小数")
    void parseValidFormats() {
        assertThat(NumericTokens.parseTargetValue("1.234,50")).isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(NumericTokens.parseTargetValue("-1.234,50")).isEqualByComparingTo(new BigDecimal("-1234.5"));
        assertThat(NumericTokens.parseTargetValue("0,5")).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(NumericTokens.parseTargetValue("42")).isEqualByComparingTo(new BigDecimal("42"));
        assertThat(NumericTokens.parseTargetValue("1.234")).isEqualByComparingTo(new BigDecimal("1234"));
        assertThat(NumericTokens.parseTargetValue("1.234.567,890123"))
                .isEqualByComparingTo(new BigDecimal("1234567.890123"));
        assertThat(NumericTokens.parseTargetValue("-0,000001"))
                .isEqualByComparingTo(new BigDecimal("-0.000001"));
    }

    @Test
    @DisplayName("目标格式解析：首组超 3 位、后组非 3 位、小数超 6 位、指数及混用格式均非法")
    void parseInvalidFormats() {
        for (String bad : new String[]{"12.34,50", "1234,5", "1.234,5012345", "1e3", "1E3",
                "1,5.5", "1.234,", ",5", "-", "", "1.23.456,5", "1.234,5.6", "abc", "1..234,5"}) {
            assertThat(NumericTokens.parseTargetValue(bad)).as("应非法: %s", bad).isNull();
        }
        assertThat(NumericTokens.parseTargetValue(null)).isNull();
    }

    @Test
    @DisplayName("源词元登记：超过 5 个或 tokenKey 重复 422；合法登记保留原值字面量")
    void validateSourceTokens() {
        List<ApiDtos.NumericTokenInput> six = java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new ApiDtos.NumericTokenInput("k" + i, BigDecimal.ONE, "米"))
                .toList();
        assertThatThrownBy(() -> NumericTokens.validateSourceTokens("s1", six))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        List<ApiDtos.NumericTokenInput> duplicate = List.of(
                new ApiDtos.NumericTokenInput("k", BigDecimal.ONE, "米"),
                new ApiDtos.NumericTokenInput("k", BigDecimal.TEN, "米"));
        assertThatThrownBy(() -> NumericTokens.validateSourceTokens("s1", duplicate))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        List<NumericTokenRow> rows = NumericTokens.validateSourceTokens("s1", List.of(
                new ApiDtos.NumericTokenInput("depth", new BigDecimal("1234.5"), "米")));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).sourceValue()).isEqualTo("1234.5");
        assertThat(NumericTokens.validateSourceTokens("s1", null)).isEmpty();
    }

    @Test
    @DisplayName("提交校核：合成场景 1.234,50 单位米通过并返回落库行")
    void validateTargetTokensSuccess() {
        List<ApiDtos.TargetNumericTokenInput> targets = List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,50", "米", 3, 11));
        List<TranslationTokenRow> rows = NumericTokens.validateTargetTokens(
                "s1", "en", SOURCE, targets, "水深 1.234,50 米");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).targetText()).isEqualTo("1.234,50");
        assertThat(rows.get(0).startOffset()).isEqualTo(3);
        assertThat(rows.get(0).endOffset()).isEqualTo(11);
    }

    @Test
    @DisplayName("提交校核：格式非法 400；错值、缺词元、多词元、单位不符 422")
    void validateTargetTokensFailures() {
        String content = "水深 1.234,50 米";
        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "12.34,50", "米", 3, 11)), content))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,51", "米", 3, 11)), content))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, null, content))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("other", "1.234,50", "米", 3, 11)), content))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,50", "m", 3, 11)), content))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("区间校核：文字不一致、越界、重叠 422；补充字符按 1 码点计不偏移")
    void validateRanges() {
        // 区间文字与目标字符串不一致
        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,50", "米", 2, 10)), "水深 1.234,50 米"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 越界
        assertThatThrownBy(() -> NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,50", "米", 3, 99)), "水深 1.234,50 米"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 重叠：两个词元区间相交
        List<NumericTokenRow> twoSources = List.of(
                new NumericTokenRow("s1", "a", "1.5", "米"), new NumericTokenRow("s1", "b", "2.5", "米"));
        List<ApiDtos.TargetNumericTokenInput> overlapping = List.of(
                new ApiDtos.TargetNumericTokenInput("a", "1,50", "米", 0, 4),
                new ApiDtos.TargetNumericTokenInput("b", "2,50", "米", 3, 7));
        assertThatThrownBy(() -> NumericTokens.validateTargetTokens(
                "s1", "en", twoSources, overlapping, "1,50 2,50"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // 补充字符 🌊（U+1F30A）占 1 个码点：词元仍在码点区间 [3,11)
        List<TranslationTokenRow> rows = NumericTokens.validateTargetTokens("s1", "en", SOURCE, List.of(
                new ApiDtos.TargetNumericTokenInput("depth", "1.234,50", "米", 3, 11)), "🌊水深1.234,50米");
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("批准/发布复核：键集合不一致或值被篡改 422；一致时通过")
    void validateStoredTokens() {
        List<TranslationTokenRow> stored = List.of(
                new TranslationTokenRow("s1", "en", "depth", "1.234,50", "米", 3, 11));
        NumericTokens.validateStoredTokens("s1", "en", SOURCE, stored, "水深 1.234,50 米");

        assertThatThrownBy(() -> NumericTokens.validateStoredTokens("s1", "en", SOURCE, List.of(),
                "水深 1.234,50 米"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        List<TranslationTokenRow> tampered = List.of(
                new TranslationTokenRow("s1", "en", "depth", "1.234,51", "米", 3, 11));
        assertThatThrownBy(() -> NumericTokens.validateStoredTokens(
                "s1", "en", SOURCE, tampered, "水深 1.234,51 米"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
