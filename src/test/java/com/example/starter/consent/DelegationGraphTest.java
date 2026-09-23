package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.consent.DelegationRepository.DelegationRow;

/**
 * 委托图纯逻辑单元测试：环检测、5 层上限与最短有效路径（BFS），不依赖数据库。
 */
class DelegationGraphTest {

    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");
    private static final Instant FUTURE = T0.plusSeconds(3600);

    private final DelegationGraph graph = new DelegationGraph();

    private DelegationRow edge(String key, String from, String to, int version, Instant expiresAt) {
        return new DelegationRow(key, "alice", Purpose.RESEARCH, 1, from, to, version,
                DelegationStatus.ACTIVE, expiresAt, null);
    }

    @Test
    void rejectsSelfLoop() {
        DelegationRow candidate = edge("k", "p1", "p1", 1, FUTURE);
        assertThat(graph.validateNewEdge(List.of(edge("a", "alice", "p1", 1, FUTURE)), "alice", candidate))
                .isPresent();
    }

    @Test
    void rejectsCycleBackToSubject() {
        List<DelegationRow> edges = List.of(
                edge("a", "alice", "p1", 1, FUTURE),
                edge("b", "p1", "p2", 1, FUTURE));
        DelegationRow candidate = edge("c", "p2", "alice", 1, FUTURE);
        assertThat(graph.validateNewEdge(edges, "alice", candidate)).isPresent();
    }

    @Test
    void rejectsEdgeFromNodeOutsideSubjectChain() {
        DelegationRow candidate = edge("c", "outsider", "p1", 1, FUTURE);
        assertThat(graph.validateNewEdge(List.of(), "alice", candidate)).isPresent();
    }

    @Test
    void allowsUpToFiveLayersAndRejectsSixth() {
        List<DelegationRow> edges = List.of(
                edge("a", "alice", "p1", 1, FUTURE),
                edge("b", "p1", "p2", 1, FUTURE),
                edge("c", "p2", "p3", 1, FUTURE),
                edge("d", "p3", "p4", 1, FUTURE));
        assertThat(graph.validateNewEdge(edges, "alice", edge("e", "p4", "p5", 1, FUTURE))).isEmpty();
        List<DelegationRow> five = List.of(
                edge("a", "alice", "p1", 1, FUTURE),
                edge("b", "p1", "p2", 1, FUTURE),
                edge("c", "p2", "p3", 1, FUTURE),
                edge("d", "p3", "p4", 1, FUTURE),
                edge("e", "p4", "p5", 1, FUTURE));
        assertThat(graph.validateNewEdge(five, "alice", edge("f", "p5", "p6", 1, FUTURE))).isPresent();
    }

    @Test
    void shortestPathPrefersDirectRouteAndIgnoresExpiredEdges() {
        List<DelegationRow> edges = List.of(
                edge("a", "alice", "p1", 1, FUTURE),
                edge("b", "p1", "p2", 1, FUTURE),
                edge("c", "alice", "p2", 2, FUTURE));
        List<DelegationRow> shortest = graph.shortestPath(edges, "alice", "p2", T0);
        assertThat(shortest).hasSize(1);
        assertThat(shortest.get(0).processorKey()).isEqualTo("p2");
        assertThat(shortest.get(0).version()).isEqualTo(2);
    }

    @Test
    void expiredEdgeBreaksReachability() {
        List<DelegationRow> edges = List.of(
                edge("a", "alice", "p1", 1, T0.plusSeconds(10)),
                edge("b", "p1", "p2", 1, FUTURE));
        // 时刻 T0+20：alice->p1 已到期，p2 不可达
        assertThat(graph.shortestPath(edges, "alice", "p2", T0.plusSeconds(20))).isNull();
        // 未到期时可达两跳
        assertThat(graph.shortestPath(edges, "alice", "p2", T0)).hasSize(2);
    }

    @Test
    void revokedStatusEdgesAreExcludedByCallerProvidedSet() {
        DelegationRow revoked = new DelegationRow("a", "alice", Purpose.RESEARCH, 1, "alice", "p1", 1,
                DelegationStatus.REVOKED, FUTURE, T0);
        assertThat(graph.shortestPath(List.of(revoked), "alice", "p1", T0)).isNull();
    }
}
