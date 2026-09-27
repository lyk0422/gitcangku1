package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 替代图环检测单元测试：自环、长环、跨制品隔离与无环场景。
 */
class DeprecationGraphTest {

    @Test
    void emptyGraphHasNoCycle() {
        assertThat(DeprecationGraph.firstCycle(Map.of())).isNull();
    }

    @Test
    void chainWithoutCyclePasses() {
        // 1 -> 2 -> 3，无环。
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 2, 2, 3));
        assertThat(DeprecationGraph.firstCycle(edges)).isNull();
    }

    @Test
    void selfLoopIsCycle() {
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 1));
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(edges);
        assertThat(cycle).isNotNull();
        assertThat(cycle.name()).isEqualTo("lib");
        assertThat(cycle.versions()).containsExactly(1, 1);
    }

    @Test
    void twoNodeCycleDetected() {
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 2, 2, 1));
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(edges);
        assertThat(cycle).isNotNull();
        assertThat(cycle.versions()).containsExactly(1, 2, 1);
    }

    @Test
    void longerCycleDetectedWithClosurePoint() {
        // 1 -> 2 -> 3 -> 2：环为 2,3,2。
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 2, 2, 3, 3, 2));
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(edges);
        assertThat(cycle).isNotNull();
        assertThat(cycle.versions()).containsExactly(2, 3, 2);
    }

    @Test
    void cyclesAreScopedPerArtifactName() {
        // 不同制品之间即使版本号相同也不构成环。
        Map<String, Map<Integer, Integer>> edges = Map.of(
                "a", Map.of(1, 2),
                "b", Map.of(2, 1));
        assertThat(DeprecationGraph.firstCycle(edges)).isNull();
    }

    @Test
    void firstCycleReturnedInLexicographicNameOrder() {
        Map<String, Map<Integer, Integer>> edges = Map.of(
                "zeta", Map.of(1, 1),
                "alpha", Map.of(2, 2));
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(edges);
        assertThat(cycle).isNotNull();
        assertThat(cycle.name()).isEqualTo("alpha");
    }

    @Test
    void cycleThroughExistingAndNewWindows() {
        // 既有窗口 1->2，新窗口 2->1：合并后出现环。
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 2, 2, 1));
        assertThat(DeprecationGraph.firstCycle(edges)).isNotNull();
    }

    @Test
    void diamondShapedAcyclicGraphPasses() {
        // 1->3, 2->3, 3->4：汇合但无环。
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(1, 3, 2, 3, 3, 4));
        assertThat(DeprecationGraph.firstCycle(edges)).isNull();
    }

    @Test
    void cycleVersionsAreStableList() {
        Map<String, Map<Integer, Integer>> edges = Map.of("lib", Map.of(3, 1, 1, 2, 2, 3));
        DeprecationGraph.Cycle cycle = DeprecationGraph.firstCycle(edges);
        assertThat(cycle).isNotNull();
        // 从最小未访问起点 1 出发：1->2->3->1。
        assertThat(cycle.versions()).isEqualTo(List.of(1, 2, 3, 1));
    }
}
