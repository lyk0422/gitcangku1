package com.example.starter.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiversionPlanner} 纯逻辑单元测试：窗口按 UTC 左闭右开判定重叠，
 * 覆盖容量边界、同级/高级不可挤、已起飞不参与（由调用方过滤）、
 * 贪心整体挤出与替换确定性，不使用数据库与随机时钟。
 */
@DisplayName("备降预留容量裁决纯逻辑")
class DiversionPlannerTest {

    private static DiversionPlanner.Occupant occ(String id, int priority, long start, long end) {
        return new DiversionPlanner.Occupant(id, "route-" + id, priority, start, end, true);
    }

    /** 构造已起飞占用：仍占容量但 displaceable=false，任何优先级都不能挤出。 */
    private static DiversionPlanner.Occupant departed(String id, int priority, long start, long end) {
        return new DiversionPlanner.Occupant(id, "route-" + id, priority, start, end, false);
    }

    @Test
    @DisplayName("空机场容量内直接接受，无需挤出")
    void acceptsWhenCapacityAvailable() {
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1, List.of(), 5, 100, 200);
        assertFalse(plan.conflict());
        assertTrue(plan.displaced().isEmpty());
        assertNull(plan.blockingReservationId());
    }

    @Test
    @DisplayName("容量1：高优先级挤出重叠的未起飞低优先级")
    void highPriorityDisplacesLower() {
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(occ("L", 1, 100, 200)), 9, 150, 250);
        assertFalse(plan.conflict());
        assertEquals(List.of("L"), plan.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
    }

    @Test
    @DisplayName("相同优先级不得互相挤出：窗口重叠即冲突并返回阻挡预留")
    void samePriorityNeverDisplaces() {
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(occ("L", 5, 100, 200)), 5, 150, 250);
        assertTrue(plan.conflict());
        assertEquals("L", plan.blockingReservationId());
        assertTrue(plan.displaced().isEmpty());
    }

    @Test
    @DisplayName("更低优先级申请遇到高级预留占用：冲突且不得挤出高级预留")
    void lowerPriorityCannotDisplaceHigher() {
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(occ("H", 9, 100, 200)), 1, 150, 250);
        assertTrue(plan.conflict());
        assertEquals("H", plan.blockingReservationId());
    }

    @Test
    @DisplayName("左闭右开：前窗结束时刻恰为后窗开始时刻不重叠，互不挤出")
    void adjacentWindowsDoNotOverlap() {
        // [100,200) 与 [200,300) 不重叠
        DiversionPlanner.Plan plan1 = DiversionPlanner.plan(1,
                List.of(occ("L", 1, 100, 200)), 9, 200, 300);
        assertFalse(plan1.conflict());
        assertTrue(plan1.displaced().isEmpty());

        // 反向：新窗 [0,100) 与既存 [100,200) 同样不重叠
        DiversionPlanner.Plan plan2 = DiversionPlanner.plan(1,
                List.of(occ("L", 1, 100, 200)), 9, 0, 100);
        assertFalse(plan2.conflict());
        assertTrue(plan2.displaced().isEmpty());

        // 相差 1 毫秒重叠（既存窗包含时刻 199）即必须挤出
        DiversionPlanner.Plan plan3 = DiversionPlanner.plan(1,
                List.of(occ("L", 1, 100, 200)), 9, 199, 300);
        assertTrue(plan3.displaced().stream()
                .anyMatch(o -> "L".equals(o.reservationId())));
    }

    @Test
    @DisplayName("容量1：高优先级新窗同时覆盖两个低优先级，二者被整体挤出")
    void displacesAllOverlappingLowerWholely() {
        // [60,100) 上 L1、L2 与新申请共 3 个占用，容量 1：两个低优先级都必须整体挤出
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(occ("L1", 1, 0, 100), occ("L2", 1, 50, 150)), 9, 60, 120);
        assertFalse(plan.conflict());
        assertEquals(List.of("L1", "L2"), plan.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
    }

    @Test
    @DisplayName("容量2：超载时段含一个同级与一个低级时，只挤低级即可容纳，不冲突")
    void displaceLowerWhileSameLevelKept() {
        // 新窗 [0,150) 与同级 S[0,100)、低级 L[50,150) 在 [50,100) 三者相遇，
        // 容量 2：挤出 L 后 S + 新申请恰好 2，合法
        DiversionPlanner.Plan plan = DiversionPlanner.plan(2,
                List.of(occ("S", 5, 0, 100), occ("L", 1, 50, 150)), 5, 0, 150);
        assertFalse(plan.conflict());
        assertEquals(List.of("L"), plan.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
    }

    @Test
    @DisplayName("容量2：超载时段有两个同级占用，低级全挤出仍超载，冲突")
    void twoSameLevelBlockersCauseConflict() {
        // [40,60) 上 S1、S2、新申请三个同级 → 无论怎么挤 L 都无法容纳
        DiversionPlanner.Plan plan = DiversionPlanner.plan(2,
                List.of(occ("S1", 5, 0, 100), occ("S2", 5, 40, 60),
                        occ("L", 1, 0, 100)), 5, 40, 60);
        assertTrue(plan.conflict());
        assertEquals("S1", plan.blockingReservationId());
        assertTrue(plan.displaced().isEmpty(), "冲突时不得产生部分挤出");
    }

    @Test
    @DisplayName("贪心：挤出结束最晚的低优先级即可释放后续全部超载时段，挤出数量最小")
    void greedyPicksLatestEndingReservation() {
        // 容量 2：L1[0,300) 结束最晚；新申请 [0,400) 在 [0,100) 与 L1、L2 相遇共 3 个，
        // 贪心挤出覆盖后续最多时段的 L1 后，[200,300) 与 L3 恰好 2 个：仅挤出 L1
        DiversionPlanner.Plan plan = DiversionPlanner.plan(2,
                List.of(occ("L1", 1, 0, 300), occ("L2", 1, 0, 100), occ("L3", 1, 200, 300)),
                9, 0, 400);
        assertFalse(plan.conflict());
        assertEquals(List.of("L1"), plan.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
    }

    @Test
    @DisplayName("分段容量：只挤出与新窗实际重叠时段所需的预留，不重叠者保留")
    void onlyOverlappingSegmentsDisplaced() {
        // 容量 1：L1[0,100) 与新窗 [100,200) 相邻不重叠；L2[150,250) 重叠
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(occ("L1", 1, 0, 100), occ("L2", 1, 150, 250)), 9, 100, 200);
        assertFalse(plan.conflict());
        assertEquals(List.of("L2"), plan.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
    }

    @Test
    @DisplayName("高优先级只在部分时段重叠：被挤预留按整窗释放，跨时段联动正确")
    void displacedReservationFreedForWholeWindow() {
        // 容量 1：L[0,200)；H1[0,100) 挤出 L 后，H2[150,300) 与 L 不再相遇
        // （调用方只传入仍占用的预留，此处模拟 L 已被移除），直接接受
        DiversionPlanner.Plan afterDisplace = DiversionPlanner.plan(1,
                List.of(occ("H1", 9, 0, 100)), 9, 150, 300);
        assertFalse(afterDisplace.conflict());
        // H1[0,100) 与 H2[150,300) 不相邻重叠
        assertTrue(afterDisplace.displaced().isEmpty());
    }

    @Test
    @DisplayName("结果确定性：相同输入多次裁决挤出清单一致且按标识排序")
    void deterministicResult() {
        List<DiversionPlanner.Occupant> current =
                List.of(occ("b", 1, 0, 100), occ("a", 1, 0, 100), occ("c", 1, 0, 100));
        DiversionPlanner.Plan first = DiversionPlanner.plan(2, current, 9, 0, 100);
        DiversionPlanner.Plan second = DiversionPlanner.plan(2, current, 9, 0, 100);
        assertEquals(List.of("a", "b"), first.displaced().stream()
                .map(DiversionPlanner.Occupant::reservationId).toList());
        assertEquals(first.displaced(), second.displaced());
    }

    @Test
    @DisplayName("已起飞低优先级仍占容量且不能被挤出：即使高优先级申请也冲突")
    void departedLowerPriorityCannotBeDisplaced() {
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(departed("L", 1, 100, 200)), 9, 150, 250);
        assertTrue(plan.conflict());
        assertEquals("L", plan.blockingReservationId());
        assertTrue(plan.displaced().isEmpty());
    }

    @Test
    @DisplayName("已起飞与未起飞低优先级并存：只能挤未起飞者，容量仍不足时冲突且不部分挤出")
    void departedBlocksWhileReservedMayGo() {
        // 容量 1：窗口 [100,200) 上一个已起飞低级 D、一个未起飞低级 L，
        // 高优先级新窗 [100,200)：挤出 L 后 D 仍阻挡 → 冲突，L 不得被部分挤出
        DiversionPlanner.Plan plan = DiversionPlanner.plan(1,
                List.of(departed("D", 1, 100, 200), occ("L", 1, 100, 200)), 9, 100, 200);
        assertTrue(plan.conflict());
        assertEquals("D", plan.blockingReservationId());
        assertTrue(plan.displaced().isEmpty());
    }

    @Test
    @DisplayName("非法容量或退化窗口抛出参数异常（服务层另负责映射为 422）")
    void illegalArgumentsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DiversionPlanner.plan(0, List.of(), 1, 0, 10));
        assertThrows(IllegalArgumentException.class,
                () -> DiversionPlanner.plan(1, List.of(), 1, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> DiversionPlanner.plan(1, List.of(), 1, 200, 100));
    }
}
