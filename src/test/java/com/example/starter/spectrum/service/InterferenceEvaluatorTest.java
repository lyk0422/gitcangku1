package com.example.starter.spectrum.service;

import com.example.starter.spectrum.dto.PlanResultResponse;
import com.example.starter.spectrum.exception.SpectrumException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 累计干扰裁决纯逻辑测试：方向、静默、恰好相等与全网络累计。
 */
class InterferenceEvaluatorTest {

    private InterferenceEvaluator.EdgeKey edge(String from, String to) {
        return new InterferenceEvaluator.EdgeKey(from, to);
    }

    @Test
    void accumulatesOnlySameChannelDirectedEdges() {
        Map<String, Integer> channels = Map.of("s1", 1, "s2", 1, "s3", 1);
        Map<String, Integer> budgets = Map.of("s1", 100, "s2", 100, "s3", 100);
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = Map.of(
                edge("s1", "s2"), 10,
                edge("s2", "s1"), 3,
                edge("s1", "s3"), 7,
                edge("s3", "s1"), 2);

        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channels, budgets, edges);

        // s1 只累计 s2->s1(3)+s3->s1(2)，方向不能倒置。
        // s2 只累计 s1->s2(10)，不把 s2->s1 算进来。
        // s3 累计 s1->s3(7)。
        assertEquals(List.of("s1", "s2", "s3"), summary.stream().map(
                PlanResultResponse.InterferenceItem::getStationId).toList());
        assertEquals(5, summary.get(0).getAccumulated());
        assertEquals(10, summary.get(1).getAccumulated());
        assertEquals(7, summary.get(2).getAccumulated());
    }

    @Test
    void silentStationsNeitherTransmitNorAreChecked() {
        // s3 静默：不发射（s1 不收 s3 的干扰），也不参与预算校验。
        Map<String, Integer> channels = Map.of("s1", 1, "s2", 1, "s3", 0);
        Map<String, Integer> budgets = Map.of("s1", 0, "s2", 0, "s3", 0);
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = Map.of(
                edge("s3", "s1"), 999,
                edge("s1", "s3"), 999,
                edge("s2", "s1"), 0,
                edge("s1", "s2"), 0);

        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channels, budgets, edges);

        assertEquals(List.of("s1", "s2"), summary.stream().map(
                PlanResultResponse.InterferenceItem::getStationId).toList());
        assertTrue(InterferenceEvaluator.findViolations(summary).isEmpty());
    }

    @Test
    void differentChannelEdgesDoNotCountAndUndefinedEdgesAreZero() {
        Map<String, Integer> channels = Map.of("s1", 1, "s2", 2);
        Map<String, Integer> budgets = Map.of("s1", 0, "s2", 0);
        // 同频道才累计；不同频道即使有边也不算。未定义边按 0。
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = Map.of(edge("s2", "s1"), 80);

        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channels, budgets, edges);

        assertEquals(0, summary.get(0).getAccumulated());
        assertEquals(0, summary.get(1).getAccumulated());
        assertTrue(InterferenceEvaluator.findViolations(summary).isEmpty());
    }

    @Test
    void exactlyEqualBudgetIsLegalButExceedingIsNot() {
        Map<String, Integer> channels = Map.of("s1", 1, "s2", 1, "s3", 1);
        Map<String, Integer> budgets = Map.of("s1", 5, "s2", 5, "s3", 4);
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = Map.of(
                edge("s2", "s1"), 5,
                edge("s1", "s2"), 6,
                edge("s3", "s3"), 100);

        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channels, budgets, edges);
        List<SpectrumException.BudgetExceeded.Violation> violations =
                InterferenceEvaluator.findViolations(summary);

        // s1 恰好等于预算合法；s2 实际 6 > 预算 5 超标；自环边不计（"其他发射台站"）。
        assertEquals(1, violations.size());
        assertEquals("s2", violations.get(0).stationId());
        assertEquals(6, violations.get(0).actual());
        assertEquals(5, violations.get(0).budget());
    }

    @Test
    void multipleTransmittersAccumulateTogether() {
        Map<String, Integer> channels = Map.of("s1", 3, "s2", 3, "s3", 3, "s4", 3);
        Map<String, Integer> budgets = Map.of("s1", 100, "s2", 100, "s3", 100, "s4", 100);
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = Map.of(
                edge("s1", "s4"), 100,
                edge("s2", "s4"), 200,
                edge("s3", "s4"), 300);

        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channels, budgets, edges);

        assertEquals(600, summary.stream()
                .filter(i -> i.getStationId().equals("s4"))
                .findFirst().orElseThrow().getAccumulated());
    }
}
