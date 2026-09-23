package com.example.starter.spectrum.service;

import com.example.starter.spectrum.domain.SpectrumNetwork;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 干扰累计裁决纯逻辑测试：方向、静默、恰好相等、全量裁决与排序。
 */
class InterferenceEvaluatorTest {

    private SpectrumNetwork network(List<SpectrumNetwork.Edge> edges, int... budgets) {
        List<SpectrumNetwork.Station> stations = new java.util.ArrayList<>();
        for (int i = 0; i < budgets.length; i++) {
            stations.add(new SpectrumNetwork.Station("s" + (i + 1), i, budgets[i], 0));
        }
        return new SpectrumNetwork("n", null, 1, stations, edges);
    }

    @Test
    void accumulatesOnlyIncomingSameChannelEdges() {
        // s1->s2=30, s2->s1=50（反向不能倒置计入 s2），s1->s3=20
        SpectrumNetwork n = network(List.of(
                new SpectrumNetwork.Edge("s1", "s2", 30),
                new SpectrumNetwork.Edge("s2", "s1", 50),
                new SpectrumNetwork.Edge("s1", "s3", 20)), 1000, 1000, 1000);

        Map<String, Integer> channels = Map.of("s1", 1, "s2", 1, "s3", 2);
        List<InterferenceEvaluator.StationResult> results = InterferenceEvaluator.evaluate(n, channels);

        var byId = new java.util.HashMap<String, InterferenceEvaluator.StationResult>();
        results.forEach(r -> byId.put(r.stationId(), r));
        // s2 只收到 s1 的 30；s1 虽与 s2 同频，但 s2->s1=50 也应计入 s1
        assertThat(byId.get("s2").total()).isEqualTo(30);
        assertThat(byId.get("s1").total()).isEqualTo(50);
        // s3 在频道2，s1 在频道1，不同频不计
        assertThat(byId.get("s3").total()).isZero();
    }

    @Test
    void silentStationNeitherTransmitsNorParticipates() {
        // s2 静默：s1 收不到 s2 的干扰，s2 自身 total=0 且不校验预算（预算0也合法）
        SpectrumNetwork n = network(List.of(
                new SpectrumNetwork.Edge("s2", "s1", 900),
                new SpectrumNetwork.Edge("s1", "s2", 900)), 0, 0);

        List<InterferenceEvaluator.StationResult> results =
                InterferenceEvaluator.evaluate(n, Map.of("s1", 1, "s2", 0));

        var byId = new java.util.HashMap<String, InterferenceEvaluator.StationResult>();
        results.forEach(r -> byId.put(r.stationId(), r));
        assertThat(byId.get("s1").total()).isZero();
        assertThat(byId.get("s1").withinBudget()).isTrue();
        assertThat(byId.get("s2").channel()).isZero();
        assertThat(byId.get("s2").total()).isZero();
        assertThat(byId.get("s2").withinBudget()).isTrue();
    }

    @Test
    void exactlyEqualToBudgetIsLegal() {
        SpectrumNetwork n = network(List.of(
                new SpectrumNetwork.Edge("s1", "s2", 60),
                new SpectrumNetwork.Edge("s3", "s2", 40)), 1000, 100, 1000);

        List<InterferenceEvaluator.StationResult> results = InterferenceEvaluator.evaluate(
                n, Map.of("s1", 5, "s2", 5, "s3", 5));

        var s2 = results.stream().filter(r -> r.stationId().equals("s2")).findFirst().orElseThrow();
        assertThat(s2.total()).isEqualTo(100);
        assertThat(s2.withinBudget()).isTrue();
        assertThat(InterferenceEvaluator.violationsSortedById(results)).isEmpty();
    }

    @Test
    void oneOverBudgetIsViolationAndMultipleSortedById() {
        SpectrumNetwork n = network(List.of(
                new SpectrumNetwork.Edge("s1", "s2", 100),
                new SpectrumNetwork.Edge("s1", "s3", 100)), 1000, 99, 50);

        List<InterferenceEvaluator.StationResult> results = InterferenceEvaluator.evaluate(
                n, Map.of("s1", 1, "s2", 1, "s3", 1));

        List<InterferenceEvaluator.StationResult> violations =
                InterferenceEvaluator.violationsSortedById(results);
        assertThat(violations).extracting(InterferenceEvaluator.StationResult::stationId)
                .containsExactly("s2", "s3");
        assertThat(violations.get(0).total()).isEqualTo(100);
        assertThat(violations.get(0).budget()).isEqualTo(99);
    }

    @Test
    void undefinedEdgesCountAsZero() {
        SpectrumNetwork n = network(List.of(), 0, 0);
        List<InterferenceEvaluator.StationResult> results = InterferenceEvaluator.evaluate(
                n, Map.of("s1", 8, "s2", 8));
        assertThat(results).allSatisfy(r -> {
            assertThat(r.total()).isZero();
            assertThat(r.withinBudget()).isTrue();
        });
    }

    @Test
    void differentChannelsDoNotInterfere() {
        SpectrumNetwork n = network(List.of(new SpectrumNetwork.Edge("s1", "s2", 1000)), 1000, 0);
        List<InterferenceEvaluator.StationResult> results = InterferenceEvaluator.evaluate(
                n, Map.of("s1", 1, "s2", 2));
        assertThat(results.get(1).total()).isZero();
    }
}
