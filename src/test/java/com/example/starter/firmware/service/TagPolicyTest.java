package com.example.starter.firmware.service;

import com.example.starter.firmware.api.TargetExpressionRequest;
import com.example.starter.firmware.domain.TagExpression;
import com.example.starter.firmware.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签规范化与目标表达式匹配纯单元测试，不启动 Spring 与数据库。
 */
class TagPolicyTest {

    private final TagPolicy policy = new TagPolicy();

    @Test
    void 标签规范化_trim并保留合法字符_大小写敏感() {
        assertThat(policy.normalizeTag("  beta-CN_1.0:ok  ")).isEqualTo("beta-CN_1.0:ok");
        assertThat(policy.normalizeTag("Beta")).isEqualTo("Beta");
        assertThat(policy.normalizeTag("beta")).isEqualTo("beta");
        assertThat(policy.normalizeTag("Beta")).isNotEqualTo(policy.normalizeTag("beta"));
    }

    @Test
    void 标签非法_空或超长或非法字符_返回422() {
        for (String raw : List.of("", "   ", "a b", "a/b", "中国标签", "a,b",
                "x".repeat(65))) {
            assertThatThrownBy(() -> policy.normalizeTag(raw))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.code()).isEqualTo("TAG_INVALID");
                    });
        }
        assertThatThrownBy(() -> policy.normalizeTag(null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("TAG_INVALID");
    }

    @Test
    void 条件组去重并字典序排序_组内重复422() {
        List<String> normalized = policy.normalizeGroup(List.of(" c ", "a", "b"), TagExpression.INCLUDE);
        assertThat(normalized).containsExactly("a", "b", "c");

        assertThatThrownBy(() -> policy.normalizeGroup(List.of("a", " a "), TagExpression.INCLUDE))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("TAG_DUPLICATE");
                    assertThat(ex.getMessage()).contains("include").contains("a");
                });
    }

    @Test
    void 表达式_空条件422() {
        assertThatThrownBy(() -> policy.normalizeExpression(
                new TargetExpressionRequest(null, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("TARGET_EXPRESSION_EMPTY");

        assertThatThrownBy(() -> policy.normalizeExpression(
                new TargetExpressionRequest(List.of(), List.of(), List.of())))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("TARGET_EXPRESSION_EMPTY");
    }

    @Test
    void 表达式_同标签包含与排除冲突422() {
        assertThatThrownBy(() -> policy.normalizeExpression(
                new TargetExpressionRequest(List.of("beta", "z"), List.of("beta"), null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("TAG_INCLUDE_EXCLUDE_CONFLICT");
                    assertThat(ex.getMessage()).contains("beta");
                });
    }

    @Test
    void 表达式规范化成功_三组排序() {
        TagExpression expression = policy.normalizeExpression(new TargetExpressionRequest(
                List.of("z", "a"), List.of("x"), List.of("m", "b")));
        assertThat(expression.include()).containsExactly("a", "z");
        assertThat(expression.exclude()).containsExactly("x");
        assertThat(expression.anyOf()).containsExactly("b", "m");
    }

    @Test
    void 匹配_必须包含() {
        TagExpression expression = policy.normalizeExpression(
                new TargetExpressionRequest(List.of("a", "b"), null, null));
        assertThat(policy.matches(Set.of("a", "b", "c"), expression)).isTrue();
        assertThat(policy.matches(Set.of("a"), expression)).isFalse();
        assertThat(policy.matches(Set.of(), expression)).isFalse();
    }

    @Test
    void 匹配_不得包含() {
        TagExpression expression = policy.normalizeExpression(
                new TargetExpressionRequest(null, List.of("x"), null));
        assertThat(policy.matches(Set.of("a"), expression)).isTrue();
        assertThat(policy.matches(Set.of("a", "x"), expression)).isFalse();
    }

    @Test
    void 匹配_至少一项() {
        TagExpression expression = policy.normalizeExpression(
                new TargetExpressionRequest(null, null, List.of("p", "q")));
        assertThat(policy.matches(Set.of("q"), expression)).isTrue();
        assertThat(policy.matches(Set.of("p", "z"), expression)).isTrue();
        assertThat(policy.matches(Set.of("z"), expression)).isFalse();
        assertThat(policy.matches(Set.of(), expression)).isFalse();
    }

    @Test
    void 匹配_三类条件组合() {
        TagExpression expression = policy.normalizeExpression(new TargetExpressionRequest(
                List.of("beta"), List.of("canary"), List.of("cn", "us")));
        assertThat(policy.matches(Set.of("beta", "cn"), expression)).isTrue();
        assertThat(policy.matches(Set.of("beta", "us", "x"), expression)).isTrue();
        // 缺少必须包含
        assertThat(policy.matches(Set.of("cn"), expression)).isFalse();
        // 命中排除
        assertThat(policy.matches(Set.of("beta", "cn", "canary"), expression)).isFalse();
        // 未命中任一 anyOf
        assertThat(policy.matches(Set.of("beta", "eu"), expression)).isFalse();
    }
}
