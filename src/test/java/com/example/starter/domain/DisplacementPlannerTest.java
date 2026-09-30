package com.example.starter.domain;

import com.example.starter.repo.ReservationPo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备降预留挤出裁决纯单元测试（无数据库、无 mock）：
 * 覆盖容量充足、高优先级整体挤出、同级/已起飞不可挤出、左闭右开边界、
 * 多预留多分段与容量 2 等边界。
 */
@DisplayName("备降预留挤出裁决")
class DisplacementPlannerTest {

    private static long seq = 0;

    private static ReservationPo reservation(String id, int priority, long start, long end,
                                             String status, boolean departed) {
        return new ReservationPo(id, "A", "route-" + id, priority, start, end,
                status, departed, "req-" + id, 1000L + (seq++));
    }

    private static ReservationPo active(String id, int priority, long start, long end) {
        return reservation(id, priority, start, end, ReservationPo.STATUS_ACTIVE, false);
    }

    private static List<String> ids(DisplacementPlanner.Decision decision) {
        return decision.displaced().stream().map(ReservationPo::reservationId).toList();
    }

    @Test
    @DisplayName("容量充足：无需挤出")
    void freeCapacityNeedsNoDisplacement() {
        List<ReservationPo> existing = List.of(active("l1", 1, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(2, 0, 100, 5, existing);
        assertTrue(d.feasible());
        assertTrue(d.displaced().isEmpty());
        assertNull(d.reason());
    }

    @Test
    @DisplayName("题干主例：容量 1，低优先级 L 已占用，高优先级 H 整体挤出 L")
    void highPriorityDisplacesLowerPriority() {
        List<ReservationPo> existing = List.of(active("L", 1, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 2, existing);
        assertTrue(d.feasible());
        assertEquals(List.of("L"), ids(d));
    }

    @Test
    @DisplayName("相同优先级不得互相挤出：409 裁决")
    void samePriorityCannotDisplace() {
        List<ReservationPo> existing = List.of(active("H0", 5, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 5, existing);
        assertFalse(d.feasible());
        assertEquals(DisplacementPlanner.BLOCKED_BY_PRIORITY, d.reason());
        assertTrue(d.displaced().isEmpty());
    }

    @Test
    @DisplayName("更高优先级占用方不可被低优先级申请挤出")
    void lowerPriorityCannotDisplaceHigher() {
        List<ReservationPo> existing = List.of(active("H", 9, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 1, existing);
        assertFalse(d.feasible());
        assertEquals(DisplacementPlanner.BLOCKED_BY_PRIORITY, d.reason());
    }

    @Test
    @DisplayName("已起飞预留即使优先级更低也不可挤出")
    void departedReservationCannotBeDisplaced() {
        ReservationPo departed = reservation("L", 1, 0, 100,
                ReservationPo.STATUS_ACTIVE, true);
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 2, List.of(departed));
        assertFalse(d.feasible());
        assertEquals(DisplacementPlanner.BLOCKED_BY_DEPARTED, d.reason());
    }

    @Test
    @DisplayName("已挤出（DISPLACED）记录不占容量，不影响新申请")
    void displacedRecordsDoNotConsumeCapacity() {
        ReservationPo displaced = reservation("old", 1, 0, 100,
                ReservationPo.STATUS_DISPLACED, false);
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 2, List.of(displaced));
        assertTrue(d.feasible());
        assertTrue(d.displaced().isEmpty());
    }

    @Test
    @DisplayName("UTC 左闭右开：首尾相接（end==start）的窗口不重叠，互不挤占")
    void halfOpenWindowsTouchingAtBoundaryDoNotOverlap() {
        assertTrue(DisplacementPlanner.overlaps(0, 100, 50, 150));
        assertFalse(DisplacementPlanner.overlaps(0, 100, 100, 200));
        assertFalse(DisplacementPlanner.overlaps(100, 200, 0, 100));

        // 现有 [0,100)，新申请 [100,200)，容量 1：不重叠，直接接纳
        List<ReservationPo> existing = List.of(active("L", 1, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 100, 200, 1, existing);
        assertTrue(d.feasible());
        assertTrue(d.displaced().isEmpty());
    }

    @Test
    @DisplayName("新窗口跨两条部分重叠的低优先级预留：两条都必须整体挤出，不能只挤一部分")
    void partiallyOverlappingReservationsBothFullyDisplaced() {
        List<ReservationPo> existing = List.of(
                active("a", 1, 0, 60),
                active("b", 1, 40, 100));
        // 容量 1，新窗口 [0,100)：[0,40) 仅 a、[40,60) a+b、[60,100) 仅 b
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 2, existing);
        assertTrue(d.feasible());
        assertEquals(2, d.displaced().size());
        assertTrue(ids(d).containsAll(List.of("a", "b")));
    }

    @Test
    @DisplayName("挤出选择：优先挤出结束最晚的低优先级预留，最小化挤出条数")
    void greedyPicksLatestEndingReservation() {
        List<ReservationPo> existing = List.of(
                active("a", 1, 0, 40),
                active("b", 1, 0, 100),
                active("c", 1, 60, 100));
        // 容量 2，新窗口 [0,100)：
        // [0,40) 有 a+b+新=3 需挤出 1 条；贪心选结束最晚的 b，
        // 之后 [60,100) 只剩 c+新=2 恰好合法，总共只挤出 1 条；
        // 若先选 a，则末段 b+c+新=3 还要再挤 b（共 2 条）。
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(2, 0, 100, 2, existing);
        assertTrue(d.feasible());
        assertEquals(List.of("b"), ids(d));
    }

    @Test
    @DisplayName("容量 2：三条低优先级 + 新申请需挤出两条")
    void capacityTwoDisplacesExactlyTwo() {
        List<ReservationPo> existing = List.of(
                active("a", 1, 0, 100),
                active("b", 1, 0, 100),
                active("c", 1, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(2, 0, 100, 2, existing);
        assertTrue(d.feasible());
        assertEquals(2, d.displaced().size());
    }

    @Test
    @DisplayName("混合阻挡：低优先级可挤出数量不足，剩余同级占用导致 409 且不选择任何挤出")
    void insufficientDisplaceableCandidatesConflictsAtomically() {
        List<ReservationPo> existing = new ArrayList<>(List.of(
                active("low", 1, 0, 100),
                active("peer", 5, 0, 100)));
        // 容量 1：新申请 priority 5，low 可挤出但 peer 同级不可挤出 → 不可行，
        // 裁决不得只挤出 low（避免“部分替换”语义）
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 5, existing);
        assertFalse(d.feasible());
        assertEquals(DisplacementPlanner.BLOCKED_BY_PRIORITY, d.reason());
        assertTrue(d.displaced().isEmpty());
    }

    @Test
    @DisplayName("新窗口仅覆盖某预留尾部：该预留整条作为候选且被整条挤出")
    void onlyTailOverlapStillDisplacesWholeReservation() {
        List<ReservationPo> existing = List.of(active("wide", 1, 0, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 80, 120, 2, existing);
        assertTrue(d.feasible());
        assertEquals(List.of("wide"), ids(d));
    }

    @Test
    @DisplayName("窗口外的高优先级占用不影响裁决")
    void nonOverlappingHigherPriorityIsIgnored() {
        List<ReservationPo> existing = List.of(active("far", 9, 200, 300));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 1, existing);
        assertTrue(d.feasible());
        assertTrue(d.displaced().isEmpty());
    }

    @Test
    @DisplayName("分段场景：前段被已起飞阻挡、后段可挤出，仍裁决 409 且不挤出任何预留")
    void departedBlockerOnOneSegmentRejectsEntireRequest() {
        List<ReservationPo> existing = List.of(
                reservation("gone", 1, 0, 40, ReservationPo.STATUS_ACTIVE, true),
                active("low", 1, 40, 100));
        DisplacementPlanner.Decision d = DisplacementPlanner.plan(1, 0, 100, 2, existing);
        assertFalse(d.feasible());
        assertEquals(DisplacementPlanner.BLOCKED_BY_DEPARTED, d.reason());
        assertTrue(d.displaced().isEmpty());
    }
}
