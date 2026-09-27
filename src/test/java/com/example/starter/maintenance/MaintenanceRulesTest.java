package com.example.starter.maintenance;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维护窗口区间与自动胜出规则的纯单元测试：左闭右开边界、重叠判定、受影响观测不可胜出与平局规则。
 */
class MaintenanceRulesTest {

    private static final Instant T0 = Instant.parse("2026-09-28T10:00:00Z");

    private SnapshotEvidence evidence(String id, Instant submittedAt, boolean affected) {
        return new SnapshotEvidence(id, submittedAt, "payload-" + id, affected,
                affected ? "win-1" : null, affected ? 1 : null);
    }

    @Test
    void containsIsHalfOpenOnBothEnds() {
        // [10:00, 11:00)：开始含、结束不含
        assertThat(MaintenanceRules.contains(T0, T0.plusSeconds(3600), T0)).isTrue();
        assertThat(MaintenanceRules.contains(T0, T0.plusSeconds(3600), T0.plusSeconds(3599))).isTrue();
        assertThat(MaintenanceRules.contains(T0, T0.plusSeconds(3600), T0.plusSeconds(3600))).isFalse();
        assertThat(MaintenanceRules.contains(T0, T0.plusSeconds(3600), T0.minusSeconds(1))).isFalse();
    }

    @Test
    void overlapsTreatsTouchingIntervalsAsNonOverlapping() {
        // 端点相接（一个结束等于另一个开始）不算重叠
        assertThat(MaintenanceRules.overlaps(T0, T0.plusSeconds(3600),
                T0.plusSeconds(3600), T0.plusSeconds(7200))).isFalse();
        assertThat(MaintenanceRules.overlaps(T0, T0.plusSeconds(3600),
                T0.plusSeconds(1800), T0.plusSeconds(5400))).isTrue();
        assertThat(MaintenanceRules.overlaps(T0, T0.plusSeconds(3600),
                T0.minusSeconds(600), T0)).isFalse();
    }

    @Test
    void pickWinnerSkipsAffectedAndTakesLatestUnaffected() {
        // 受影响观测即使提交更晚也不可胜出；未受影响中取提交时刻最晚者
        List<SnapshotEvidence> evidence = List.of(
                evidence("obs-a", T0, false),
                evidence("obs-b", T0.plusSeconds(120), true),
                evidence("obs-c", T0.plusSeconds(60), false));
        Optional<SnapshotEvidence> winner = MaintenanceRules.pickWinner(evidence);
        assertThat(winner).isPresent();
        assertThat(winner.get().observationId()).isEqualTo("obs-c");
    }

    @Test
    void pickWinnerTieBreaksByObservationIdAscending() {
        List<SnapshotEvidence> evidence = List.of(
                evidence("obs-z", T0, false),
                evidence("obs-a", T0, false),
                evidence("obs-m", T0, false));
        Optional<SnapshotEvidence> winner = MaintenanceRules.pickWinner(evidence);
        assertThat(winner).isPresent();
        assertThat(winner.get().observationId()).isEqualTo("obs-a");
    }

    @Test
    void pickWinnerEmptyWhenAllAffectedOrNoEvidence() {
        assertThat(MaintenanceRules.pickWinner(List.of()).isEmpty()).isTrue();
        assertThat(MaintenanceRules.pickWinner(List.of(
                evidence("obs-a", T0, true),
                evidence("obs-b", T0.plusSeconds(60), true))).isEmpty()).isTrue();
    }
}
