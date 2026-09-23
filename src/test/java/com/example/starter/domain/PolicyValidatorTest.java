package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略发布期静态校验单元测试：候选优先级、原坐标与替代坐标相同、
 * 规则重叠、静态坐标环与生效时刻。
 */
class PolicyValidatorTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

    private static SubstitutionCandidate cand(String coordinate, int priority) {
        return new SubstitutionCandidate(coordinate, priority);
    }

    private static SubstitutionRule rule(String source, String platform, Instant effectiveAt,
                                         String... priorityAndTarget) {
        java.util.List<SubstitutionCandidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < priorityAndTarget.length; i += 2) {
            candidates.add(cand(priorityAndTarget[i + 1], Integer.parseInt(priorityAndTarget[i])));
        }
        return new SubstitutionRule(0L, CoordinatePattern.of(source), platform,
                candidates, effectiveAt);
    }

    private static void validate(SubstitutionRule... rules) {
        PolicyValidator.validate(List.of(rules));
    }

    @Test
    void acceptsValidPolicy() {
        assertThatCode(() -> validate(
                rule("a", "jvm", NOW, "1", "b"),
                rule("c", "jvm", NOW, "1", "d", "2", "e")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyPolicy() {
        assertThatThrownBy(() -> PolicyValidator.validate(List.of()))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void rejectsSourceEqualToCandidate() {
        assertThatThrownBy(() -> validate(rule("a", "jvm", NOW, "1", "a")))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("不能相同");
    }

    @Test
    void rejectsNonContiguousPriorities() {
        assertThatThrownBy(() -> validate(rule("a", "jvm", NOW, "1", "b", "3", "c")))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("连续");
    }

    @Test
    void rejectsDuplicatePriorities() {
        assertThatThrownBy(() -> {
            SubstitutionRule duplicate = new SubstitutionRule(0L, CoordinatePattern.of("a"),
                    "jvm", List.of(cand("b", 1), cand("c", 1)), NOW);
            PolicyValidator.validate(List.of(duplicate));
        }).isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void rejectsDuplicateCandidateCoordinates() {
        assertThatCode(() -> {
            SubstitutionRule duplicate = new SubstitutionRule(0L, CoordinatePattern.of("a"),
                    "jvm", List.of(cand("b", 1), cand("b", 2)), NOW);
            PolicyValidator.validate(List.of(duplicate));
        }).isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("重复候选坐标");
    }

    @Test
    void rejectsOverlappingExactAndWildcardRulesOnSamePlatform() {
        assertThatThrownBy(() -> validate(
                rule("com.old.A", "jvm", NOW, "1", "x"),
                rule("com.old.*", "jvm", NOW, "1", "y")))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void rejectsTwoOverlappingWildcardRules() {
        assertThatThrownBy(() -> validate(
                rule("com.old.*", "jvm", NOW, "1", "x"),
                rule("com.*", "jvm", NOW, "1", "y")))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void allowsOverlappingSourcesOnDifferentPlatforms() {
        assertThatCode(() -> validate(
                rule("com.old.A", "jvm", NOW, "1", "x"),
                rule("com.old.A", "native", NOW, "1", "y")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDirectCycleAtoBtoA() {
        assertThatThrownBy(() -> validate(
                rule("a", "jvm", NOW, "1", "b"),
                rule("b", "jvm", NOW, "1", "a")))
                .isInstanceOf(SubstitutionFailureException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsLongerCycle() {
        assertThatThrownBy(() -> validate(
                rule("a", "jvm", NOW, "1", "b"),
                rule("b", "jvm", NOW, "1", "c"),
                rule("c", "jvm", NOW, "1", "a")))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void rejectsCycleThroughWildcardSource() {
        // a -> com.x.b；com.x.* -> a：沿候选 com.x.b 命中通配规则回到 a。
        assertThatThrownBy(() -> validate(
                rule("a", "jvm", NOW, "1", "com.x.b"),
                rule("com.x.*", "jvm", NOW, "1", "a")))
                .isInstanceOf(SubstitutionFailureException.class);
    }

    @Test
    void acceptsAcyclicChain() {
        assertThatCode(() -> validate(
                rule("a", "jvm", NOW, "1", "b"),
                rule("b", "jvm", NOW, "1", "c"),
                rule("c", "jvm", NOW, "1", "d")))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsPastEffectiveAtBecauseTimeGatingHappensAtResolution() {
        assertThatCode(() -> PolicyValidator.validate(
                List.of(rule("a", "jvm", NOW.minusSeconds(1), "1", "b"))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsFutureEffectiveAt() {
        assertThatCode(() -> validate(rule("a", "jvm", NOW.plusSeconds(3600), "1", "b")))
                .doesNotThrowAnyException();
    }
}
