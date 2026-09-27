package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 弃用裁决策略单元测试：左闭右开时间区间、稳定排序与替代环检测。
 */
class DeprecationPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static DeprecationWindow window(long id, String name, int version,
                                            Instant effectiveAt, int replacement) {
        return new DeprecationWindow(id, name, version, effectiveAt, replacement, "原因" + id);
    }

    private static RepositorySnapshot snapshot(DeprecationWindow... windows) {
        Map<String, Map<Integer, DeprecationWindow>> map = new java.util.TreeMap<>();
        for (DeprecationWindow w : windows) {
            map.computeIfAbsent(w.name(), k -> new java.util.TreeMap<>())
                    .put(w.version(), w);
        }
        return new RepositorySnapshot(0, Map.of(), map);
    }

    @Test
    void beforeEffectiveIsWarning() {
        RepositorySnapshot s = snapshot(window(1, "lib", 1, T0.plusSeconds(3600), 2));
        DeprecationPolicy.Adjudication result =
                DeprecationPolicy.adjudicate(s, Map.of("lib", 1), T0);
        assertThat(result.violations()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0).replacementVersion()).isEqualTo(2);
    }

    @Test
    void atEffectiveInstantIsViolation() {
        // 左闭右开：now == effectiveAt 视为已生效。
        RepositorySnapshot s = snapshot(window(1, "lib", 1, T0, 2));
        DeprecationPolicy.Adjudication result =
                DeprecationPolicy.adjudicate(s, Map.of("lib", 1), T0);
        assertThat(result.warnings()).isEmpty();
        assertThat(result.violations()).hasSize(1);
    }

    @Test
    void afterEffectiveIsViolation() {
        RepositorySnapshot s = snapshot(window(1, "lib", 1, T0, 2));
        DeprecationPolicy.Adjudication result =
                DeprecationPolicy.adjudicate(s, Map.of("lib", 1), T0.plusSeconds(1));
        assertThat(result.violations()).hasSize(1);
    }

    @Test
    void closureMissesWindowIsIgnored() {
        RepositorySnapshot s = snapshot(window(1, "lib", 1, T0, 2));
        DeprecationPolicy.Adjudication result =
                DeprecationPolicy.adjudicate(s, Map.of("lib", 2, "app", 1), T0.plusSeconds(1));
        assertThat(result.warnings()).isEmpty();
        assertThat(result.violations()).isEmpty();
    }

    @Test
    void warningsAndViolationsAreSortedByName() {
        RepositorySnapshot s = snapshot(
                window(1, "zlib", 1, T0.plusSeconds(3600), 2),
                window(2, "alib", 1, T0.plusSeconds(3600), 2),
                window(3, "mlib", 1, T0.minusSeconds(1), 2),
                window(4, "blib", 1, T0.minusSeconds(1), 2));
        Map<String, Integer> closure = Map.of("zlib", 1, "alib", 1, "mlib", 1, "blib", 1);
        DeprecationPolicy.Adjudication result = DeprecationPolicy.adjudicate(s, closure, T0);
        assertThat(result.warnings()).extracting(DeprecationWindow::name)
                .containsExactly("alib", "zlib");
        assertThat(result.violations()).extracting(DeprecationWindow::name)
                .containsExactly("blib", "mlib");
    }

    @Test
    void detectsDirectCycle() {
        assertThat(DeprecationPolicy.graphHasCycle(Map.of(1, 2, 2, 1))).isTrue();
    }

    @Test
    void detectsSelfLoop() {
        assertThat(DeprecationPolicy.graphHasCycle(Map.of(1, 1))).isTrue();
    }

    @Test
    void detectsIndirectCycle() {
        assertThat(DeprecationPolicy.graphHasCycle(Map.of(1, 2, 2, 3, 3, 1))).isTrue();
    }

    @Test
    void acceptsAcyclicChain() {
        assertThat(DeprecationPolicy.graphHasCycle(Map.of(1, 2, 2, 3, 4, 5))).isFalse();
    }

    @Test
    void acceptsEmptyGraph() {
        assertThat(DeprecationPolicy.graphHasCycle(Map.of())).isFalse();
    }
}
