package com.example.starter.service;

import com.example.starter.repo.ReservationPo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 探测查询扫描线峰值占用算法的纯单元测试：左闭右开、首尾相接不叠加、
 * 部分重叠取窗内裁剪区间。
 */
class PeakOccupancyTest {

    private static final long MIN = 60_000L;

    private static ReservationPo r(String key, long start, long end) {
        return new ReservationPo(key, "c", start, end, "rv", "ACTIVE",
                "req-" + key, 0L, null);
    }

    @Test
    void emptyWindowHasZeroPeak() {
        assertEquals(0, CorridorReservationService.peakOccupancy(List.of(), 0L, 60 * MIN));
    }

    @Test
    void halfOpenAdjacentIntervalsDoNotStack() {
        List<ReservationPo> rs = List.of(
                r("a", 0L, 30 * MIN),
                r("b", 30 * MIN, 60 * MIN));
        assertEquals(1, CorridorReservationService.peakOccupancy(rs, 0L, 60 * MIN));
    }

    @Test
    void overlappingIntervalsStackToPeak() {
        List<ReservationPo> rs = List.of(
                r("a", 0L, 40 * MIN),
                r("b", 10 * MIN, 50 * MIN),
                r("c", 20 * MIN, 30 * MIN));
        assertEquals(3, CorridorReservationService.peakOccupancy(rs, 0L, 60 * MIN));
    }

    @Test
    void intervalsAreClippedToProbeWindow() {
        // 预约完全包含探测窗
        assertEquals(1, CorridorReservationService.peakOccupancy(
                List.of(r("a", 0L, 100 * MIN)), 20 * MIN, 30 * MIN));
        // 探测窗只覆盖相邻两段中的一段 → 峰值 1
        List<ReservationPo> rs = List.of(
                r("a", 0L, 20 * MIN),
                r("b", 20 * MIN, 40 * MIN));
        assertEquals(1, CorridorReservationService.peakOccupancy(rs, 20 * MIN, 40 * MIN));
        // 跨越相接点两侧的窗仍只在各自区间计数，首尾相接不叠加
        assertEquals(1, CorridorReservationService.peakOccupancy(rs, 10 * MIN, 30 * MIN));
    }
}
