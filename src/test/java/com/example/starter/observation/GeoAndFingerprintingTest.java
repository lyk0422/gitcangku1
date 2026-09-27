package com.example.starter.observation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 围栏几何校验与幂等指纹的纯单元测试（不启动 Spring 上下文）。
 */
class GeoAndFingerprintingTest {

    private final Fingerprinting fingerprinting = new Fingerprinting(
            new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void pointRangeValidationAcceptsBoundariesAndRejectsOutOfRange() {
        // 合法范围含端点
        GeoValidation.validatePoint(new BigDecimal("-90"), new BigDecimal("-180"));
        GeoValidation.validatePoint(new BigDecimal("90"), new BigDecimal("180"));

        assertThatThrownBy(() -> GeoValidation.validatePoint(
                new BigDecimal("-90.0000001"), new BigDecimal("0")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(422));
        assertThatThrownBy(() -> GeoValidation.validatePoint(
                new BigDecimal("0"), new BigDecimal("180.0000001")))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(422));
    }

    @Test
    void fenceBoundsRejectInvertedAndZeroWidthRectangles() {
        // 正常矩形
        GeoValidation.validateFenceBounds(new BigDecimal("31.0"), new BigDecimal("32.0"),
                new BigDecimal("121.0"), new BigDecimal("122.0"));
        // 上下界相等（零高度）：422
        assertThatThrownBy(() -> GeoValidation.validateFenceBounds(
                new BigDecimal("31.0"), new BigDecimal("31.0"),
                new BigDecimal("121.0"), new BigDecimal("122.0")))
                .isInstanceOf(ApiException.class);
        // 下颠倒：422
        assertThatThrownBy(() -> GeoValidation.validateFenceBounds(
                new BigDecimal("32.0"), new BigDecimal("31.0"),
                new BigDecimal("121.0"), new BigDecimal("122.0")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void effectiveIntervalMustBeStrictlyOrdered() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-01T00:00:00Z");
        GeoValidation.validateEffectiveInterval(from, to);
        // 零长度区间非法
        assertThatThrownBy(() -> GeoValidation.validateEffectiveInterval(from, from))
                .isInstanceOf(ApiException.class);
        // 倒序区间非法
        assertThatThrownBy(() -> GeoValidation.validateEffectiveInterval(to, from))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void fingerprintIsStableAcrossEquivalentNumericForms() {
        String a = fingerprinting.of("SUBMIT_BATCH", "op", List.of(List.of(
                "sub-1", "obs-1", "site-1", 1, "loc", "1.2", "note",
                new BigDecimal("31.50"), new BigDecimal("121.500"),
                Instant.parse("2026-09-15T08:00:00Z"))));
        String b = fingerprinting.of("SUBMIT_BATCH", "op", List.of(List.of(
                "sub-1", "obs-1", "site-1", 1, "loc", "1.2", "note",
                new BigDecimal("31.500"), new BigDecimal("121.5"),
                Instant.parse("2026-09-15T08:00:00Z"))));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprintChangesWhenAnyResultAffectingFieldChanges() {
        Object[] base = {"SUBMIT_BATCH", "op", List.of(List.of(
                "sub-1", "obs-1", "site-1", 1, "loc", "1.2", "note",
                new BigDecimal("31.5"), new BigDecimal("121.5"),
                Instant.parse("2026-09-15T08:00:00Z")))};
        String baseFingerprint = fingerprinting.of(base);
        // 操作者变化
        assertThat(fingerprinting.of("SUBMIT_BATCH", "other", base[2])).isNotEqualTo(baseFingerprint);
        // 操作类型变化
        assertThat(fingerprinting.of("ADJUDICATE", "op", base[2])).isNotEqualTo(baseFingerprint);
        // 集合内坐标变化
        assertThat(fingerprinting.of("SUBMIT_BATCH", "op", List.of(List.of(
                "sub-1", "obs-1", "site-1", 1, "loc", "1.2", "note",
                new BigDecimal("31.6"), new BigDecimal("121.5"),
                Instant.parse("2026-09-15T08:00:00Z"))))).isNotEqualTo(baseFingerprint);
    }

    @Test
    void fingerprintDoesNotCollideOnUnescapedSeparators() {
        // 字段值中出现 JSON 结构性字符与控制字符，也不能改变字段边界
        String a = fingerprinting.of("FENCE_CREATE", "op", "a\u0001", "b");
        String b = fingerprinting.of("FENCE_CREATE", "op", "a", "\u0001b");
        String c = fingerprinting.of("FENCE_CREATE", "op", "a\",\"x\":[", "b");
        String d = fingerprinting.of("FENCE_CREATE", "op", "a", "\",\"x\":[b");
        assertThat(a).isNotEqualTo(b);
        assertThat(c).isNotEqualTo(d);
    }
}
