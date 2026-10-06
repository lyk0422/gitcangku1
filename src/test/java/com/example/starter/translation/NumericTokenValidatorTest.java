package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiDtos;
import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.NumericTokenRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数值词元格式与码点区间的纯单元测试：固定目标格式（逗号小数点、点三位分组符）、
 * 精确值解析与 Unicode 码点切片（补充字符计 1 个码点）。
 */
class NumericTokenValidatorTest {

    private static final List<NumericTokenRow> SOURCE = NumericTokenValidator.validateSourceTokens(
            List.of(new ApiDtos.NumericTokenInput("n1", "1234.5", "米")));

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0,5", "123", "1.234", "1.234,50", "-1.234,5", "1.234.567,123456", "0,000001"})
    @DisplayName("合法目标格式：负号、首组 1~3 位、后组恰 3 位、小数 1~6 位")
    void validTargetFormats(String targetValue) {
        String content = "x" + targetValue + "x";
        var rows = NumericTokenValidator.validateTargetTokens(
                NumericTokenValidator.validateSourceTokens(
                        List.of(new ApiDtos.NumericTokenInput("n1",
                                NumericTokenValidator.parseTargetValue(targetValue).toPlainString(), "米"))),
                content,
                List.of(new ApiDtos.TargetNumericTokenInput("n1", targetValue, "米", 1,
                        1 + targetValue.length())));
        assertThat(rows).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.34,50", "1234", "1234,5", "1,2345678", "1.23,4", "+1", "1e3",
            "1.234.567,1234567", "1,5,6", "1..234,5", "1.2345", "-", "1,", ".123", "1.234,"})
    @DisplayName("非法目标格式：分组错位、指数、混用分隔符等一律 400")
    void invalidTargetFormats(String targetValue) {
        String content = "x" + targetValue + "x";
        assertThatThrownBy(() -> NumericTokenValidator.validateTargetTokens(SOURCE, content,
                List.of(new ApiDtos.TargetNumericTokenInput("n1", targetValue, "米", 1,
                        1 + targetValue.length()))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("解析精确值：去分组点、逗号换小数点，按值比较")
    void parseTargetValue() {
        assertThat(NumericTokenValidator.parseTargetValue("1.234,50"))
                .isEqualByComparingTo(new BigDecimal("1234.5"));
        assertThat(NumericTokenValidator.parseTargetValue("-0,5"))
                .isEqualByComparingTo(new BigDecimal("-0.5"));
        assertThat(NumericTokenValidator.parseTargetValue("1.234.567,123456"))
                .isEqualByComparingTo(new BigDecimal("1234567.123456"));
    }

    @Test
    @DisplayName("码点切片：补充字符（如 emoji）计 1 个码点，不产生代理对偏移")
    void sliceByCodePointsWithSupplementaryCharacter() {
        String content = "🎉1.234,50 米";
        assertThat(content.length()).isEqualTo(12);
        assertThat(content.codePointCount(0, content.length())).isEqualTo(11);
        assertThat(NumericTokenValidator.sliceByCodePoints(content, 1, 9)).isEqualTo("1.234,50");
    }

    @Test
    @DisplayName("源值登记：非法十进制 400，tokenKey 重复 422")
    void sourceTokenRegistration() {
        assertThatThrownBy(() -> NumericTokenValidator.validateSourceTokens(
                List.of(new ApiDtos.NumericTokenInput("n1", "1e3", "米"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> NumericTokenValidator.validateSourceTokens(
                List.of(new ApiDtos.NumericTokenInput("n1", "1.5", "米"),
                        new ApiDtos.NumericTokenInput("n1", "2.5", "米"))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    @DisplayName("值不符 422、单位不符 422、缺词元 422、区间重叠 422")
    void businessViolations() {
        String content = "深度 1.234,51 米";
        assertThatThrownBy(() -> NumericTokenValidator.validateTargetTokens(SOURCE, content,
                List.of(new ApiDtos.TargetNumericTokenInput("n1", "1.234,51", "米", 3, 11))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> NumericTokenValidator.validateTargetTokens(SOURCE, "深度 1.234,50 米",
                List.of(new ApiDtos.TargetNumericTokenInput("n1", "1.234,50", "千克", 3, 11))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> NumericTokenValidator.validateTargetTokens(SOURCE, "深度 1.234,50 米",
                List.of()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        List<NumericTokenRow> twoSources = NumericTokenValidator.validateSourceTokens(List.of(
                new ApiDtos.NumericTokenInput("a", "1.5", "米"),
                new ApiDtos.NumericTokenInput("b", "1.5", "米")));
        assertThatThrownBy(() -> NumericTokenValidator.validateTargetTokens(twoSources, "1,5",
                List.of(new ApiDtos.TargetNumericTokenInput("a", "1,5", "米", 0, 3),
                        new ApiDtos.TargetNumericTokenInput("b", "1,5", "米", 0, 3))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
}
