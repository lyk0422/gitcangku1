package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.consent.DelegationRepository.DelegationRow;

/**
 * 委托图纯逻辑单元测试：最短路径、最大层数、环检测与到期边过滤。
 */
class DelegationGraphTest {

    private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final Instant PAST = Instant.parse("2000-01-01T00:00:00Z");

    private final DelegationGraph graph = new DelegationGraph();

    private DelegationRow edge(String key, String from, String to, int version, Instant expiresAt) {
        return new DelegationRow(key, "subj", Purpose.RESEARCH, 1, from, to, version,
                DelegationStatus.ACTIVE, expiresAt, "req-" + key);
    }

    @Test
    void subjectItselfIsEmptyPathDistanceZero() {
        assertThat(graph.shortestPath(List.of(), "subj", "subj", NOW)).isEmpty();
        assertThat(graph.shortestDistance(List.of(), "subj", "subj", NOW)).isZero();
    }

    @Test
    void singleHopChainResolves() {
        List<DelegationRow> edges = List.of(edge("d1", "subj", "p1", 1, FUTURE));
        List<DelegationRow> path = graph.shortestPath(edges, "subj", "p1", NOW);
        assertThat(path).hasSize(1);
        assertThat(path.get(0).delegationKey()).isEqualTo("d1");
        assertThat(graph.shortestDistance(edges, "subj", "p1", NOW)).isEqualTo(1);
    }

    @Test
    void unreachableProcessorHasNoPath() {
        List<DelegationRow> edges = List.of(edge("d1", "subj", "p1", 1, FUTURE));
        assertThat(graph.shortestPath(edges, "subj", "pX", NOW)).isNull();
        assertThat(graph.shortestDistance(edges, "subj", "pX", NOW)).isEqualTo(-1);
    }

    @Test
    void expiredEdgesAreIgnored() {
        List<DelegationRow> edges = List.of(edge("d1", "subj", "p1", 1, PAST));
        assertThat(graph.shortestPath(edges, "subj", "p1", NOW)).isNull();
    }

    @Test
    void shortestPathPrefersFewerHops() {
        // 一条三跳长链 subj->a->b->target 与一条直达边 subj->target
        List<DelegationRow> edges = List.of(
                edge("long1", "subj", "a", 1, FUTURE),
                edge("long2", "a", "b", 1, FUTURE),
                edge("long3", "b", "target", 1, FUTURE),
                edge("direct", "subj", "target", 1, FUTURE));
        List<DelegationRow> path = graph.shortestPath(edges, "subj", "target", NOW);
        assertThat(path).hasSize(1);
        assertThat(path.get(0).delegationKey()).isEqualTo("direct");
    }

    @Test
    void fiveHopChainIsAllowedSixthHopIsRejectedByDepth() {
        List<DelegationRow> fiveHops = List.of(
                edge("d1", "subj", "p1", 1, FUTURE),
                edge("d2", "p1", "p2", 1, FUTURE),
                edge("d3", "p2", "p3", 1, FUTURE),
                edge("d4", "p3", "p4", 1, FUTURE),
                edge("d5", "p4", "p5", 1, FUTURE));
        assertThat(graph.shortestPath(fiveHops, "subj", "p5", NOW)).hasSize(5);

        List<DelegationRow> sixHops = new java.util.ArrayList<>(fiveHops);
        sixHops.add(edge("d6", "p5", "p6", 1, FUTURE));
        assertThat(graph.shortestPath(sixHops, "subj", "p6", NOW)).isNull();
    }

    @Test
    void cycleDetectionForNewEdge() {
        List<DelegationRow> edges = List.of(
                edge("d1", "subj", "p1", 1, FUTURE),
                edge("d2", "p1", "p2", 1, FUTURE));
        // p2 -> subj 成环
        assertThat(graph.wouldCreateCycle(edges, "subj", "p2", "subj", NOW)).isTrue();
        // p2 -> p1 成环（p1 可达 p2，再加反向边）
        assertThat(graph.wouldCreateCycle(edges, "subj", "p2", "p1", NOW)).isTrue();
        // p2 -> p3 不成环
        assertThat(graph.wouldCreateCycle(edges, "subj", "p2", "p3", NOW)).isFalse();
    }

    @Test
    void selfLoopAndDelegatingToSubjectRejected() {
        assertThat(graph.wouldCreateCycle(List.of(), "subj", "subj", "subj", NOW)).isTrue();
        assertThat(graph.wouldCreateCycle(List.of(edge("d1", "subj", "p1", 1, FUTURE)),
                "subj", "p1", "p1", NOW)).isTrue();
    }

    @Test
    void expiredBackEdgeDoesNotCountAsCycle() {
        // 已到期的 p2->p1 不参与有效性判定，但成环检查针对“当前有效图”，故新边 p2->p3 安全
        List<DelegationRow> edges = List.of(
                edge("d1", "subj", "p1", 1, FUTURE),
                edge("d2", "p1", "p2", 1, FUTURE),
                edge("old", "p2", "p1", 1, PAST));
        assertThat(graph.wouldCreateCycle(edges, "subj", "p2", "p3", NOW)).isFalse();
    }
}
