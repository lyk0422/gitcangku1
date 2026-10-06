package com.example.starter.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通信覆盖评估器纯单元测试：合并并集、跨段连续失联、精确有理数阈值。
 */
class LinkCoverageEvaluatorTest {

    private static SegmentLink seg(long durationMs, int... fromTo) {
        List<CoverageInterval> coverage = new java.util.ArrayList<>();
        for (int i = 0; i < fromTo.length; i += 2) {
            coverage.add(new CoverageInterval(fromTo[i], fromTo[i + 1]));
        }
        return new SegmentLink(durationMs, coverage);
    }

    @Test
    void synthesisJunctionExactlyAtLimitIsAllowed() {
        // 题干合成样例：两段各 10 秒，第一段仅覆盖前 80%，第二段仅覆盖后 70%
        // 相接失联 2s + 3s = 5s，正好等于 5000ms → 允许
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 8000),
                seg(10000, 3000, 10000)));
        assertFalse(result.anyLossExceedsLimit());
        assertEquals(1, result.gaps().size());
        LinkGap gap = result.gaps().get(0);
        // 跨段缺口连成同一段连续失联
        assertEquals(0, gap.fromSegment());
        assertEquals(1, gap.toSegment());
        assertEquals(50_000_000L, gap.lossTickMillis());
        assertEquals("5000", gap.lossMillisText());
        // 合并并集原样保存
        assertEquals(List.of(new CoverageInterval(0, 8000)),
                result.union().get(0).intervals());
        assertEquals(List.of(new CoverageInterval(3000, 10000)),
                result.union().get(1).intervals());
    }

    @Test
    void synthesisJunctionSlightlyLongerIsBlocked() {
        // 第二段改为仅覆盖后 69%：相接失联 2s + 3.1s = 5.1s > 5s → 阻断
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 8000),
                seg(10000, 3100, 10000)));
        assertTrue(result.anyLossExceedsLimit());
        assertEquals("5100", result.gaps().get(0).lossMillisText());
    }

    @Test
    void exactRationalBoundaryWithoutPrematureRounding() {
        // 单段 10 秒，尾部缺口 5000 刻度 → 恰好 5000ms 允许；5001 刻度 → 5001ms 阻断
        LinkAssessment atLimit = LinkCoverageEvaluator.evaluate(List.of(seg(10000, 0, 5000)));
        assertFalse(atLimit.anyLossExceedsLimit());
        assertEquals("5000", atLimit.gaps().get(0).lossMillisText());

        LinkAssessment overLimit = LinkCoverageEvaluator.evaluate(List.of(seg(10000, 0, 4999)));
        assertTrue(overLimit.anyLossExceedsLimit());
        assertEquals("5001", overLimit.gaps().get(0).lossMillisText());
    }

    @Test
    void emptyCoverageMeansWholeSegmentLost() {
        // 空覆盖合法：全段失联，10 秒 > 5 秒 → 阻断
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(seg(10000)));
        assertTrue(result.anyLossExceedsLimit());
        assertEquals(1, result.gaps().size());
        assertEquals(0, result.gaps().get(0).fromSegment());
        assertEquals(0, result.gaps().get(0).toSegment());
        assertEquals("10000", result.gaps().get(0).lossMillisText());
        assertTrue(result.union().get(0).intervals().isEmpty());
    }

    @Test
    void overlappingIntervalsMergedWithoutDoubleCounting() {
        // 重叠区间合并后满覆盖：无失联；重叠部分不双计
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 4000, 10000, 0, 6000)));
        assertTrue(result.gaps().isEmpty());
        assertFalse(result.anyLossExceedsLimit());
        assertEquals(List.of(new CoverageInterval(0, 10000)),
                result.union().get(0).intervals());
    }

    @Test
    void touchingIntervalsProduceNoZeroLengthGap() {
        // 端点相接的覆盖 [0,5000] 与 [5000,10000]：不要求覆盖终点的零时长缺口
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 5000, 5000, 10000)));
        assertTrue(result.gaps().isEmpty());
        assertEquals(List.of(new CoverageInterval(0, 10000)),
                result.union().get(0).intervals());
    }

    @Test
    void internalGapWithinOneSegmentIsSinglePeriod() {
        // 段内中部缺口 2000 刻度 × 10 秒 = 2000ms，单段区间，允许
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 4000, 6000, 10000)));
        assertEquals(1, result.gaps().size());
        LinkGap gap = result.gaps().get(0);
        assertEquals(0, gap.fromSegment());
        assertEquals(0, gap.toSegment());
        assertEquals("2000", gap.lossMillisText());
        assertFalse(result.anyLossExceedsLimit());
    }

    @Test
    void fullyUncoveredMiddleSegmentChainsAcrossThreeSegments() {
        // 三段各 10 秒：段0尾缺 1000 刻度、段1全段失联、段2头缺 1000 刻度
        // 连成一段跨三段连续失联 (1000+10000+1000)×10000/10000 = 12000ms → 阻断
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 9000),
                seg(10000),
                seg(10000, 1000, 10000)));
        assertEquals(1, result.gaps().size());
        LinkGap gap = result.gaps().get(0);
        assertEquals(0, gap.fromSegment());
        assertEquals(2, gap.toSegment());
        assertEquals("12000", gap.lossMillisText());
        assertTrue(result.anyLossExceedsLimit());
    }

    @Test
    void separatedGapsDoNotChainAcrossCoveredBoundary() {
        // 段0尾部有缺口但段1头部被覆盖：两段失联各自独立，不连成一段
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(
                seg(10000, 0, 9000),
                seg(10000, 0, 9000)));
        assertEquals(2, result.gaps().size());
        assertEquals("1000", result.gaps().get(0).lossMillisText());
        assertEquals("1000", result.gaps().get(1).lossMillisText());
        assertFalse(result.anyLossExceedsLimit());
    }

    @Test
    void fractionalMillisKeptExactly() {
        // 精确有理数：1 刻度 × 1ms / 10000 = 0.0001ms，不提前取整为 0
        LinkAssessment result = LinkCoverageEvaluator.evaluate(List.of(seg(1, 0, 9999)));
        assertEquals(1, result.gaps().size());
        assertEquals("0.0001", result.gaps().get(0).lossMillisText());
        assertFalse(result.anyLossExceedsLimit());
    }
}
