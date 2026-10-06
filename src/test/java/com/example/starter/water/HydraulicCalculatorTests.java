package com.example.starter.water;

import com.example.starter.water.HydraulicCalculator.HeadExceededException;
import com.example.starter.water.HydraulicCalculator.Request;
import com.example.starter.water.HydraulicCalculator.Segment;
import com.example.starter.water.HydraulicCalculator.SliceFact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 简化水力模型纯单元测试：切片、共用段流量聚合、水头边界与精确整数溢出，不依赖数据库。
 */
class HydraulicCalculatorTests {

    private static final long MIN = 60_000_000_000L;

    private static Segment seg(long id, long k) {
        return new Segment(id, "seg-" + id, k, 1);
    }

    private static Request req(long id, long flow, long startMin, long endMin, long head,
                               List<Segment> path) {
        return new Request(id, "ak-" + id, flow, startMin * MIN, endMin * MIN, head, path);
    }

    @Test
    void syntheticCaseSinglePassesButCombinedExceeds() {
        Request a = req(1, 6, 0, 10, 100, List.of(seg(1, 1)));
        List<SliceFact> alone = HydraulicCalculator.recompute(List.of(a));
        assertEquals(1, alone.size());
        assertEquals(6, alone.get(0).totalFlow());
        assertEquals(36, alone.get(0).loss());

        Request b = req(2, 6, 0, 10, 100, List.of(seg(1, 1)));
        HeadExceededException e = assertThrows(HeadExceededException.class,
                () -> HydraulicCalculator.recompute(List.of(a, b)));
        assertEquals(144, e.loss());
        assertEquals(100, e.availableHead());
    }

    @Test
    void headExactlyEqualToLossPasses() {
        // 损失 1 × 12^2 = 144，水头恰好 144 可通过，143 不可
        Request exact = req(1, 12, 0, 10, 144, List.of(seg(1, 1)));
        assertDoesNotThrow(() -> HydraulicCalculator.recompute(List.of(exact)));
        Request shortByOne = req(2, 12, 0, 10, 143, List.of(seg(1, 1)));
        assertThrows(HeadExceededException.class,
                () -> HydraulicCalculator.recompute(List.of(shortByOne)));
    }

    @Test
    void slicesAreSplitByAllCurveBoundaries() {
        Request a = req(1, 6, 0, 10, 100, List.of(seg(1, 1)));
        Request b = req(2, 6, 5, 15, 100, List.of(seg(1, 1)));
        // A 在 [5,10) 与 B 叠加：12^2 = 144 > 100，A 越限
        HeadExceededException e = assertThrows(HeadExceededException.class,
                () -> HydraulicCalculator.recompute(List.of(a, b)));
        assertEquals("ak-1", e.allocationKey());
        assertEquals(5 * MIN, e.sliceStartNanos());
    }

    @Test
    void nonOverlappingRequestsOnSameSegmentDoNotAggregate() {
        Request a = req(1, 6, 0, 10, 100, List.of(seg(1, 1)));
        Request b = req(2, 6, 10, 20, 100, List.of(seg(1, 1)));
        List<SliceFact> facts = HydraulicCalculator.recompute(List.of(a, b));
        assertEquals(2, facts.size());
        assertTrue(facts.stream().allMatch(f -> f.totalFlow() == 6 && f.loss() == 36));
    }

    @Test
    void pathLossSumsAllSegmentsPerSlice() {
        Request a = req(1, 6, 0, 10, 100, List.of(seg(1, 1), seg(2, 2)));
        // 段损失 1*36 + 2*36 = 108 > 100
        HeadExceededException e = assertThrows(HeadExceededException.class,
                () -> HydraulicCalculator.recompute(List.of(a)));
        assertEquals(108, e.loss());
        // 水头恰好 108 可通过
        Request ok = req(2, 6, 0, 10, 108, List.of(seg(1, 1), seg(2, 2)));
        assertDoesNotThrow(() -> HydraulicCalculator.recompute(List.of(ok)));
    }

    @Test
    void exactIntegerOverflowIsDetected() {
        Request huge = req(1, 4_000_000_000L, 0, 10, Long.MAX_VALUE, List.of(seg(1, 1)));
        assertThrows(ArithmeticException.class, () -> HydraulicCalculator.recompute(List.of(huge)));
    }

    @Test
    void minutesBetweenAndMinuteAlignment() {
        assertEquals(90, HydraulicCalculator.minutesBetween(0, 90 * MIN));
        assertTrue(HydraulicCalculator.isMinuteAligned(120 * MIN));
        assertTrue(HydraulicCalculator.isMinuteAligned(0));
        assertTrue(!HydraulicCalculator.isMinuteAligned(30_000_000_000L));
    }
}
