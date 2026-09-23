package com.example.starter.consent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.starter.consent.ApiException;

/**
 * 范围守恒与替代环校验的纯单元测试，不依赖数据库或 Spring。
 */
class RangeConservationValidatorTest {

    private final LongRange source = new LongRange(0L, 100L);

    private PurposeTarget target(String purpose, long start, long end, String supersedes) {
        return new PurposeTarget(purpose, new LongRange(start, end), supersedes);
    }

    @Test
    void acceptsTwoDisjointSubsetsCoveringSource() {
        List<PurposeTarget> targets = List.of(
                target("RESEARCH_A", 0, 50, null),
                target("RESEARCH_B", 50, 100, null));
        RangeConservationValidator.validate(source, targets, Set.of("RESEARCH"), Map.of());
    }

    @Test
    void acceptsSubsetsWithGapBecauseUnionMustNotExpand() {
        // 并集 [0,40) U [60,100) 是旧范围的真子集，空隙属性映射为 UNMAPPED
        List<PurposeTarget> targets = List.of(
                target("RESEARCH_A", 0, 40, null),
                target("RESEARCH_B", 60, 100, null));
        RangeConservationValidator.validate(source, targets, Set.of("RESEARCH"), Map.of());
        assertThat(RangeConservationValidator.uniqueTarget(targets, 50L)).isNull();
        assertThat(RangeConservationValidator.uniqueTarget(targets, 39L)).isEqualTo("RESEARCH_A");
        assertThat(RangeConservationValidator.uniqueTarget(targets, 60L)).isEqualTo("RESEARCH_B");
    }

    @Test
    void rejectsTargetCountOutsideTwoToTen() {
        List<PurposeTarget> one = List.of(target("A", 0, 50, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, one, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2～10");

        List<PurposeTarget> eleven = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> {
                    long start = i * 9L;
                    return target("T" + i, start, Math.min(start + 9, 100), null);
                })
                .toList();
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, eleven, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("2～10");
    }

    @Test
    void rejectsRangeThatExpandsBeyondSource() {
        List<PurposeTarget> targets = List.of(
                target("A", 0, 60, null),
                target("B", 60, 101, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("越出旧用途范围");
    }

    @Test
    void rejectsOverlappingRangesBecauseAttributeWouldHitMultipleTargets() {
        List<PurposeTarget> targets = List.of(
                target("A", 0, 60, null),
                target("B", 50, 100, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void rejectsDuplicateNewPurposeCode() {
        List<PurposeTarget> targets = List.of(
                target("SAME", 0, 50, null),
                target("SAME", 50, 100, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsPurposeCodeAlreadyInCatalog() {
        List<PurposeTarget> targets = List.of(
                target("RESEARCH", 0, 50, null),
                target("RESEARCH_B", 50, 100, null));
        assertThatThrownBy(() ->
                RangeConservationValidator.validate(source, targets, Set.of("RESEARCH"), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void rejectsEmptyRange() {
        List<PurposeTarget> targets = List.of(
                target("A", 50, 50, null),
                target("B", 50, 100, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("非空");
    }

    @Test
    void acceptsSupersedeChainWithoutCycle() {
        // 历史边：OLD_RESEARCH -> RESEARCH；新用途 A 替代 OLD_RESEARCH，B 替代 A 不构成环
        List<PurposeTarget> targets = List.of(
                target("A", 0, 50, "OLD_RESEARCH"),
                target("B", 50, 100, "A"));
        RangeConservationValidator.validate(source, targets, Set.of("RESEARCH", "OLD_RESEARCH"),
                Map.of("OLD_RESEARCH", "RESEARCH"));
    }

    @Test
    void rejectsSupersedeSelfLoop() {
        List<PurposeTarget> targets = List.of(
                target("A", 0, 50, "A"),
                target("B", 50, 100, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsSupersedeCycleAcrossNewPurposes() {
        // A -> B 且 B -> A 形成环
        List<PurposeTarget> targets = List.of(
                target("A", 0, 50, "B"),
                target("B", 50, 100, "A"));
        assertThatThrownBy(() -> RangeConservationValidator.validate(source, targets, Set.of(), Map.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("环");
    }

    @Test
    void rejectsCycleThatRunsThroughHistory() {
        // 历史边 X -> A（X 曾替代 A），新用途 A 声明替代 X，构成 A -> X -> A 环
        List<PurposeTarget> cyclic = List.of(
                target("A", 0, 50, "X"),
                target("B", 50, 100, null));
        assertThatThrownBy(() -> RangeConservationValidator.validate(
                source, cyclic, Set.of("X"), Map.of("X", "A")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("环");
    }
}
