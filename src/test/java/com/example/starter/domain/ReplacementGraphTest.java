package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 替代关系图环检测单元测试：自环、二/三元环、无环链与重复边，结论须稳定。
 */
class ReplacementGraphTest {

    private static ReplacementGraph.Edge edge(long deprecated, long replacement) {
        return new ReplacementGraph.Edge(deprecated, replacement);
    }

    @Test
    void acyclicChainReturnsEmptyCycle() {
        List<ReplacementGraph.Edge> edges = List.of(
                edge(1, 2), edge(2, 3), edge(4, 2));
        assertThat(ReplacementGraph.findCycle(edges)).isEmpty();
    }

    @Test
    void emptyGraphReturnsEmptyCycle() {
        assertThat(ReplacementGraph.findCycle(List.of())).isEmpty();
    }

    @Test
    void selfReplacementIsACycle() {
        // 弃用版本将自己作为替代版本：自环，422。
        assertThat(ReplacementGraph.findCycle(List.of(edge(7, 7))))
                .containsExactly(7L);
    }

    @Test
    void twoNodeCycleIsDetected() {
        List<ReplacementGraph.Edge> edges = List.of(edge(1, 2), edge(2, 1));
        assertThat(ReplacementGraph.findCycle(edges)).containsExactly(1L, 2L);
    }

    @Test
    void threeNodeCycleIsDetectedAndStableRegardlessOfInsertOrder() {
        List<ReplacementGraph.Edge> orderA = List.of(edge(1, 2), edge(2, 3), edge(3, 1));
        List<ReplacementGraph.Edge> orderB = List.of(edge(3, 1), edge(1, 2), edge(2, 3));
        assertThat(ReplacementGraph.findCycle(orderA)).containsExactly(1L, 2L, 3L);
        assertThat(ReplacementGraph.findCycle(orderB)).containsExactly(1L, 2L, 3L);
    }

    @Test
    void cycleAlongsideUnrelatedEdgesIsStillReported() {
        // 9->8->9 成环，1->2 为无关边；结果只含环上节点并升序。
        List<ReplacementGraph.Edge> edges = List.of(edge(1, 2), edge(9, 8), edge(8, 9));
        assertThat(ReplacementGraph.findCycle(edges)).containsExactly(8L, 9L);
    }

    @Test
    void duplicateEdgeWithSameTargetIsAcceptedAsSamePostState() {
        // 批量完整后态合并后同一来源只会剩一条边；相同边重复出现不应误报。
        assertThatCode(() -> ReplacementGraph.findCycle(List.of(edge(1, 2), edge(1, 2))))
                .doesNotThrowAnyException();
        assertThat(ReplacementGraph.findCycle(List.of(edge(1, 2), edge(1, 2)))).isEmpty();
    }
}
