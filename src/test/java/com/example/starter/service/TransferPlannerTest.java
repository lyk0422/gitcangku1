package com.example.starter.service;

import com.example.starter.api.dto.BucketRefDto;
import com.example.starter.api.dto.TransferItemRequest;
import com.example.starter.domain.BucketSlot;
import com.example.starter.repo.PlanSlotPo;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.ZonePo;
import com.example.starter.service.TransferPlanner.Directive;
import com.example.starter.service.TransferPlanner.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转配纯计算引擎单元测试：覆盖 A→B→C→A 闭环、项换序等价、
 * 路径/禁飞/集合/容量失败，以及未参与航线占用计入。
 */
class TransferPlannerTest {

    private static final long T0 = 0L;

    // 闭环几何：
    // X=cell(1,1) Y=cell(2,1) Z=cell(1,2)
    // 航线 A：锚点 cell(2,2)，X→Y；航线 B：锚点 cell(2,2)，Y→Z；
    // 航线 C：锚点 cell(0,2)，Z→X
    private static final BucketSlot X = new BucketSlot(1, 1, T0);
    private static final BucketSlot Y = new BucketSlot(2, 1, T0);
    private static final BucketSlot Z = new BucketSlot(1, 2, T0);
    private static final BucketSlot ANCHOR_AB = new BucketSlot(2, 2, T0);
    private static final BucketSlot ANCHOR_C = new BucketSlot(0, 2, T0);

    private static PlanSlotPo slot(int seq, BucketSlot b) {
        return new PlanSlotPo(seq, b.cellX(), b.cellY(), b.bucketStart());
    }

    private static RoutePo route(String id, int version) {
        return new RoutePo(id, version, List.of());
    }

    private static TransferItemRequest item(String routeId, BucketSlot from, BucketSlot to) {
        return new TransferItemRequest(routeId, 1, 1,
                new BucketRefDto(from.cellX(), from.cellY(), from.bucketStart()),
                new BucketRefDto(to.cellX(), to.cellY(), to.bucketStart()));
    }

    private static Map<String, RoutePo> routes(String... ids) {
        Map<String, RoutePo> map = new LinkedHashMap<>();
        for (String id : ids) {
            map.put(id, route(id, 1));
        }
        return map;
    }

    private static Map<String, List<PlanSlotPo>> cycleBeforePlans() {
        Map<String, List<PlanSlotPo>> plans = new LinkedHashMap<>();
        plans.put("A", List.of(slot(0, ANCHOR_AB), slot(1, X)));
        plans.put("B", List.of(slot(0, ANCHOR_AB), slot(1, Y)));
        plans.put("C", List.of(slot(0, ANCHOR_C), slot(1, Z)));
        return plans;
    }

    private static List<TransferItemRequest> cycleItems() {
        return List.of(item("A", X, Y), item("B", Y, Z), item("C", Z, X));
    }

    private static Map<BucketSlot, Integer> cycleCapacity() {
        Map<BucketSlot, Integer> cap = new HashMap<>();
        cap.put(X, 1);
        cap.put(Y, 1);
        cap.put(Z, 1);
        cap.put(ANCHOR_AB, 2);
        cap.put(ANCHOR_C, 1);
        return cap;
    }

    /** 全量基线（含未参与航线）：X/Y/Z 各 1，锚点桶 (2,2)=2、(0,2)=1。 */
    private static Map<BucketSlot, Integer> cycleBaseline() {
        Map<BucketSlot, Integer> base = new HashMap<>();
        base.put(X, 1);
        base.put(Y, 1);
        base.put(Z, 1);
        base.put(ANCHOR_AB, 2);
        base.put(ANCHOR_C, 1);
        return base;
    }

    private static Outcome planCycle(Map<BucketSlot, Integer> capacity,
                                     Map<BucketSlot, Integer> baseline,
                                     List<TransferItemRequest> items,
                                     List<ZonePo> zones) {
        Map<String, RoutePo> routeMap = routes("A", "B", "C");
        return TransferPlanner.plan(routeMap, cycleBeforePlans(),
                TransferPlanner.normalizeDirectives(items), baseline, capacity, zones);
    }

    @Test
    void closedLoopIsFeasibleWhenComputedAsCompletePostState() {
        Outcome outcome = planCycle(cycleCapacity(), cycleBaseline(), cycleItems(), List.of());
        assertTrue(outcome.feasible(), "闭环按完整后态必须可行: " + outcome.violations);
        // 逐项先占用后释放会在 Y/Z/X 上误判为 2；完整后态各桶恰好 1
        assertEquals(1, outcome.usedAfter.get(X));
        assertEquals(1, outcome.usedAfter.get(Y));
        assertEquals(1, outcome.usedAfter.get(Z));
        // 锚点桶不变
        assertEquals(2, outcome.usedAfter.get(ANCHOR_AB));
        assertEquals(1, outcome.usedAfter.get(ANCHOR_C));
        // 后态序列：A→Y、B→Z、C→X
        assertEquals(Y, new BucketSlot(outcome.afterPlans.get("A").get(1).cellX(),
                outcome.afterPlans.get("A").get(1).cellY(),
                outcome.afterPlans.get("A").get(1).bucketStart()));
        assertEquals(Z, new BucketSlot(outcome.afterPlans.get("B").get(1).cellX(),
                outcome.afterPlans.get("B").get(1).cellY(),
                outcome.afterPlans.get("B").get(1).bucketStart()));
        assertEquals(X, new BucketSlot(outcome.afterPlans.get("C").get(1).cellX(),
                outcome.afterPlans.get("C").get(1).cellY(),
                outcome.afterPlans.get("C").get(1).bucketStart()));
    }

    @Test
    void itemOrderDoesNotChangeOutcome() {
        List<TransferItemRequest> shuffled = new ArrayList<>(cycleItems());
        java.util.Collections.reverse(shuffled);
        shuffled.add(shuffled.remove(0));
        Outcome a = planCycle(cycleCapacity(), cycleBaseline(), cycleItems(), List.of());
        Outcome b = planCycle(cycleCapacity(), cycleBaseline(), shuffled, List.of());
        assertEquals(a.feasible(), b.feasible());
        assertEquals(a.usedAfter, b.usedAfter);
        // 规范化指令稳定排序
        List<Directive> d1 = TransferPlanner.normalizeDirectives(cycleItems());
        List<Directive> d2 = TransferPlanner.normalizeDirectives(shuffled);
        assertEquals(d1, d2);
    }

    @Test
    void nonParticipantOccupancyIsCountedAndCanBreakCapacity() {
        // 未参与航线 D 已占 X 一架次：闭环后 X 仍有 C 与 D，共 2 > 上限 1
        Map<BucketSlot, Integer> baseline = cycleBaseline();
        baseline.put(X, 2);
        Outcome outcome = planCycle(cycleCapacity(), baseline, cycleItems(), List.of());
        assertFalse(outcome.feasible());
        assertTrue(outcome.violations.stream().anyMatch(v ->
                TransferPlanner.V_CAPACITY_EXCEEDED.equals(v.type())
                        && v.detail().contains("cell(1,1)")));
    }

    @Test
    void missingSourceBucketIsItemNotInPlan() {
        BucketSlot ghost = new BucketSlot(9, 9, T0);
        Outcome outcome = planCycle(cycleCapacity(), cycleBaseline(),
                List.of(item("A", ghost, Y), item("B", Y, Z), item("C", Z, X)), List.of());
        assertTrue(outcome.violations.stream().anyMatch(v ->
                TransferPlanner.V_ITEM_NOT_IN_PLAN.equals(v.type()) && "A".equals(v.routeId())));
    }

    @Test
    void discontinuousTargetRejected() {
        // A 的目标换成与锚点 cell(2,2) 不相邻的 cell(5,5)
        BucketSlot far = new BucketSlot(5, 5, T0);
        Outcome outcome = planCycle(cycleCapacity(), cycleBaseline(),
                List.of(item("A", X, far), item("B", Y, Z), item("C", Z, X)), List.of());
        assertTrue(outcome.violations.stream().anyMatch(v ->
                TransferPlanner.V_DISCONTINUOUS_PATH.equals(v.type()) && "A".equals(v.routeId())));
    }

    @Test
    void noFlyConflictOnTargetCellRejected() {
        // 禁飞区只覆盖目标 Y=cell(2,1) 内部 [41,60]×[20,39]；
        // 与锚点 (2,2)（y 从 40 起）、X=(1,1)（x 到 40 止）、Z=(1,2) 均不相交
        ZonePo zone = new ZonePo("z1", 41, 20, 60, 39, "ACTIVE", 0L, null);
        Outcome outcome = planCycle(cycleCapacity(), cycleBaseline(), cycleItems(),
                List.of(zone));
        assertTrue(outcome.violations.stream().anyMatch(v ->
                TransferPlanner.V_NO_FLY_CONFLICT.equals(v.type())
                        && "A".equals(v.routeId())
                        && v.detail().contains("z1")));
        // B 的后态 Z=cell(1,2) 矩形 [20,40]×[40,60] 不与区域相交
        assertTrue(outcome.violations.stream().noneMatch(v ->
                TransferPlanner.V_NO_FLY_CONFLICT.equals(v.type()) && "B".equals(v.routeId())));
    }

    @Test
    void duplicateSlotsInSameBucketCountAsOneFlight() {
        // 航线 R 两个槽位同在 X，两条同桶指令都搬到 Y；同一架次在 Y 只计 1
        Map<String, RoutePo> routeMap = routes("R");
        Map<String, List<PlanSlotPo>> before = new LinkedHashMap<>();
        before.put("R", List.of(slot(0, X), slot(1, X)));
        List<TransferItemRequest> items = List.of(item("R", X, Y), item("R", X, Y));
        Map<BucketSlot, Integer> cap = new HashMap<>();
        cap.put(X, 1);
        cap.put(Y, 1);
        Map<BucketSlot, Integer> base = new HashMap<>();
        base.put(X, 1);
        base.put(Y, 0);
        Outcome outcome = TransferPlanner.plan(routeMap, before,
                TransferPlanner.normalizeDirectives(items), base, cap, List.of());
        // 两个槽位都搬到 Y 后相邻槽位同单元同桶，路径仍连续
        assertTrue(outcome.feasible(), outcome.violations.toString());
        assertEquals(0, outcome.usedAfter.get(X));
        assertEquals(1, outcome.usedAfter.get(Y));
    }
}
