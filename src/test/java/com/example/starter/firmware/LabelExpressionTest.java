package com.example.starter.firmware;

import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签表达式规范化、422 失败分支与匹配真值的纯单元测试。
 */
class LabelExpressionTest {

    @Test
    void 规范化_去空白_字典序排序() {
        LabelExpression expression = LabelExpression.of(
                List.of("  beta ", "alpha"), List.of(" delta "), List.of("z", " a "));
        assertThat(expression.mustInclude()).containsExactly("alpha", "beta");
        assertThat(expression.mustExclude()).containsExactly("delta");
        assertThat(expression.includeAny()).containsExactly("a", "z");
    }

    @Test
    void 组内重复标签返回422() {
        assertThatThrownBy(() -> LabelExpression.of(List.of("a", " a"), List.of(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("LABEL_DUPLICATED");
                });
    }

    @Test
    void 空标签返回422() {
        assertThatThrownBy(() -> LabelExpression.of(List.of("   "), List.of(), List.of()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("LABEL_INVALID");
                });
        assertThatThrownBy(() -> LabelExpression.of(null, List.of(""), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 三组条件全空返回422() {
        assertThatThrownBy(() -> LabelExpression.of(null, null, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("EMPTY_LABEL_CONDITION");
                });
        assertThatThrownBy(() -> LabelExpression.of(List.of(), List.of(), List.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 同标签同时包含与排除返回422() {
        assertThatThrownBy(() -> LabelExpression.of(List.of("canary"), List.of("canary"), null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("LABEL_INCLUDE_EXCLUDE_CONFLICT");
                });
        // 跨组 trim 规范化后冲突也应识别
        assertThatThrownBy(() -> LabelExpression.of(List.of(" canary "), List.of("canary"), null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void 必须包含_全部满足才匹配() {
        LabelExpression expression = LabelExpression.of(List.of("a", "b"), null, null);
        assertThat(expression.matches(List.of("a", "b", "c"))).isTrue();
        assertThat(expression.matches(List.of("a"))).isFalse();
        assertThat(expression.matches(List.of())).isFalse();
    }

    @Test
    void 不得包含_命中任一即不匹配() {
        LabelExpression expression = LabelExpression.of(null, List.of("banned"), null);
        assertThat(expression.matches(List.of("banned"))).isFalse();
        assertThat(expression.matches(List.of("other"))).isTrue();
        assertThat(expression.matches(List.of())).isTrue();
    }

    @Test
    void 至少包含一项_命中任一即匹配_全不命中拒绝() {
        LabelExpression expression = LabelExpression.of(null, null, List.of("x", "y"));
        assertThat(expression.matches(List.of("x"))).isTrue();
        assertThat(expression.matches(List.of("y", "z"))).isTrue();
        assertThat(expression.matches(List.of("z"))).isFalse();
        assertThat(expression.matches(List.of())).isFalse();
    }

    @Test
    void 三类条件组合按合取求值() {
        LabelExpression expression = LabelExpression.of(
                List.of("region-cn"), List.of("blacklist"), List.of("canary", "beta"));
        assertThat(expression.matches(List.of("region-cn", "canary"))).isTrue();
        assertThat(expression.matches(List.of("region-cn", "beta"))).isTrue();
        // 缺少任一必须包含
        assertThat(expression.matches(List.of("canary"))).isFalse();
        // 命中不得包含
        assertThat(expression.matches(List.of("region-cn", "canary", "blacklist"))).isFalse();
        // 至少一项全不命中
        assertThat(expression.matches(List.of("region-cn", "stable"))).isFalse();
    }

    @Test
    void 大小写敏感且未规范化输入也按原值匹配() {
        LabelExpression expression = LabelExpression.of(List.of("Canary"), null, null);
        assertThat(expression.matches(List.of("Canary"))).isTrue();
        assertThat(expression.matches(List.of("canary"))).isFalse();
    }

    @Test
    void 设备标签规范化_排序去重_空集合合法() {
        assertThat(LabelExpression.normalizeTags(List.of(" b ", "a", "a"))).containsExactly("a", "b");
        assertThat(LabelExpression.normalizeTags(null)).isEmpty();
        assertThat(LabelExpression.normalizeTags(List.of())).isEmpty();
        assertThatThrownBy(() -> LabelExpression.normalizeTags(List.of(" ")))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("LABEL_INVALID"));
    }
}
