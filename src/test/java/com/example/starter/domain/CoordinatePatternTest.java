package com.example.starter.domain;

import com.example.starter.api.dto.PublishPolicyRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 原坐标通配匹配与策略发布校验单元测试。
 */
class CoordinatePatternTest {

    private static Coordinate c(String name, int version) {
        return new Coordinate(name, version);
    }

    @Test
    void exactAndWildcardMatching() {
        assertThat(CoordinatePattern.parse("lib:1").matches(c("lib", 1))).isTrue();
        assertThat(CoordinatePattern.parse("lib:1").matches(c("lib", 2))).isFalse();
        assertThat(CoordinatePattern.parse("lib").matches(c("lib", 9))).isTrue();
        assertThat(CoordinatePattern.parse("lib-*").matches(c("lib-core", 1))).isTrue();
        assertThat(CoordinatePattern.parse("lib-?").matches(c("lib-x", 1))).isTrue();
        assertThat(CoordinatePattern.parse("lib-?").matches(c("lib-xy", 1))).isFalse();
        assertThat(CoordinatePattern.parse("lib:*").matches(c("lib", 123))).isTrue();
    }

    @Test
    void overlapDetection() {
        assertThat(CoordinatePattern.overlap("lib:1", "lib:1")).isTrue();
        assertThat(CoordinatePattern.overlap("lib:1", "lib:2")).isFalse();
        assertThat(CoordinatePattern.overlap("lib:*", "lib:1")).isTrue();
        assertThat(CoordinatePattern.overlap("lib-*", "lib-core:1")).isTrue();
        assertThat(CoordinatePattern.overlap("lib:?", "lib:1")).isTrue();
        assertThat(CoordinatePattern.overlap("a:1", "b:1")).isFalse();
    }

    private static PublishPolicyRequest.AlternativeSpec alt(String name, int version) {
        return new PublishPolicyRequest.AlternativeSpec(name, version);
    }

    private static PublishPolicyRequest.RuleSpec ruleSpec(String pattern, String platform,
                                                          PublishPolicyRequest.AlternativeSpec... alts) {
        return new PublishPolicyRequest.RuleSpec(pattern, platform,
                Instant.parse("2025-01-01T00:00:00Z"), List.of(alts));
    }

    @Test
    void rejectsSelfSubstitution() {
        List<PublishPolicyRequest.RuleSpec> rules = List.of(
                ruleSpec("lib:1", "p", alt("lib", 1)));
        assertThatThrownBy(() -> PolicyValidator.validate(rules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相同");
    }

    @Test
    void rejectsOverlappingRulesOnSamePlatform() {
        List<PublishPolicyRequest.RuleSpec> rules = List.of(
                ruleSpec("lib:1", "p", alt("a", 1)),
                ruleSpec("lib:*", "p", alt("b", 1)));
        assertThatThrownBy(() -> PolicyValidator.validate(rules))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void allowsOverlappingPatternsOnDifferentPlatforms() {
        List<PublishPolicyRequest.RuleSpec> rules = List.of(
                ruleSpec("lib:1", "p1", alt("a", 1)),
                ruleSpec("lib:*", "p2", alt("b", 1)));
        PolicyValidator.validate(rules);
    }
}
