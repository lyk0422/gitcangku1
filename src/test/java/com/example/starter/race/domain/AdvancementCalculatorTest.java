package com.example.starter.race.domain;

import com.example.starter.race.domain.AdvancementCalculator.AdvanceEntry;
import com.example.starter.race.domain.AdvancementCalculator.AdvanceType;
import com.example.starter.race.domain.AdvancementCalculator.AdvancementOutcome;
import com.example.starter.race.domain.AdvancementCalculator.GroupInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdvancementCalculator} 的纯逻辑测试：直接晋级、补位、并列超额与名额不足。
 */
class AdvancementCalculatorTest {

    @Test
    void 组内前Q直接晋级_剩余全局前W补位() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3")),
                new GroupInput("B", List.of("b1", "b2", "b3")));
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 200L, "a3", 300L,
                "b1", 150L, "b2", 250L, "b3", 350L);

        AdvancementOutcome outcome = AdvancementCalculator.compute(groups, totals, 1, 1);

        AdvancementOutcome.Plan plan = (AdvancementOutcome.Plan) outcome;
        assertThat(plan.expectedCount()).isEqualTo(3);
        assertThat(plan.actualCount()).isEqualTo(3);
        assertThat(plan.overflowReason()).isNull();
        assertThat(plan.entries()).containsExactly(
                new AdvanceEntry("a1", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("b1", "B", AdvanceType.DIRECT, 1, 150L),
                new AdvanceEntry("a2", "A", AdvanceType.WILDCARD, 1, 200L));
    }

    @Test
    void 第Q名并列时全部纳入并给出超额原因() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3")),
                new GroupInput("B", List.of("b1", "b2")));
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 100L, "a3", 300L,
                "b1", 500L, "b2", 600L);

        AdvancementOutcome.Plan plan =
                (AdvancementOutcome.Plan) AdvancementCalculator.compute(groups, totals, 1, 0);

        assertThat(plan.expectedCount()).isEqualTo(2);
        assertThat(plan.actualCount()).isEqualTo(3);
        assertThat(plan.overflowReason()).contains("并列");
        assertThat(plan.entries()).containsExactly(
                new AdvanceEntry("a1", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("a2", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("b1", "B", AdvanceType.DIRECT, 1, 500L));
    }

    @Test
    void 补位边界并列时全部纳入() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3")),
                new GroupInput("B", List.of("b1", "b2", "b3")));
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 200L, "a3", 200L,
                "b1", 150L, "b2", 400L, "b3", 500L);

        // Q=1：a1、b1 直接晋级；补位池 a2=200、a3=200、b2=400，W=1 边界并列 a2/a3 全部纳入
        AdvancementOutcome.Plan plan =
                (AdvancementOutcome.Plan) AdvancementCalculator.compute(groups, totals, 1, 1);

        assertThat(plan.expectedCount()).isEqualTo(3);
        assertThat(plan.actualCount()).isEqualTo(4);
        assertThat(plan.overflowReason()).contains("补位");
        assertThat(plan.entries()).containsExactly(
                new AdvanceEntry("a1", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("b1", "B", AdvanceType.DIRECT, 1, 150L),
                new AdvanceEntry("a2", "A", AdvanceType.WILDCARD, 1, 200L),
                new AdvanceEntry("a3", "A", AdvanceType.WILDCARD, 1, 200L));
    }

    @Test
    void 名次并列同名次并跳号() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3", "a4")),
                new GroupInput("B", List.of("b1", "b2", "b3")));
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 100L, "a3", 300L, "a4", 400L,
                "b1", 500L, "b2", 600L, "b3", 700L);

        // Q=3：A 组 a1/a2 并列第1，a3 第3
        AdvancementOutcome.Plan plan =
                (AdvancementOutcome.Plan) AdvancementCalculator.compute(groups, totals, 3, 0);

        assertThat(plan.entries()).containsExactly(
                new AdvanceEntry("a1", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("a2", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("a3", "A", AdvanceType.DIRECT, 3, 300L),
                new AdvanceEntry("b1", "B", AdvanceType.DIRECT, 1, 500L),
                new AdvanceEntry("b2", "B", AdvanceType.DIRECT, 2, 600L),
                new AdvanceEntry("b3", "B", AdvanceType.DIRECT, 3, 700L));
    }

    @Test
    void 分组有效选手不足Q时整体失败并返回该分组() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3")),
                new GroupInput("B", List.of("b1", "b2", "b3")));
        // b3 无成绩（无效），B 组有效选手仅 2 名，Q=3 不足；A 组 3 名充足
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 200L, "a3", 300L,
                "b1", 150L, "b2", 250L);

        AdvancementOutcome outcome = AdvancementCalculator.compute(groups, totals, 3, 0);

        AdvancementOutcome.Insufficient insufficient =
                (AdvancementOutcome.Insufficient) outcome;
        assertThat(insufficient.groupCode()).isEqualTo("B");
        assertThat(insufficient.required()).isEqualTo(3);
        assertThat(insufficient.actual()).isEqualTo(2);
    }

    @Test
    void 无成绩成员不参与晋级也不占用补位候选位置() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2", "a3")),
                new GroupInput("B", List.of("b1", "b2", "b3")));
        // a3 无成绩：不直接晋级也不进入补位池；Q=1 后补位池为 a2=200、b2=250、b3=350
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 200L,
                "b1", 150L, "b2", 250L, "b3", 350L);

        AdvancementOutcome.Plan plan =
                (AdvancementOutcome.Plan) AdvancementCalculator.compute(groups, totals, 1, 1);

        assertThat(plan.actualCount()).isEqualTo(3);
        assertThat(plan.entries()).containsExactly(
                new AdvanceEntry("a1", "A", AdvanceType.DIRECT, 1, 100L),
                new AdvanceEntry("b1", "B", AdvanceType.DIRECT, 1, 150L),
                new AdvanceEntry("a2", "A", AdvanceType.WILDCARD, 1, 200L));
    }

    @Test
    void W为0时不产生补位() {
        List<GroupInput> groups = List.of(
                new GroupInput("A", List.of("a1", "a2")),
                new GroupInput("B", List.of("b1", "b2")));
        Map<String, Long> totals = Map.of(
                "a1", 100L, "a2", 200L,
                "b1", 150L, "b2", 250L);

        AdvancementOutcome.Plan plan =
                (AdvancementOutcome.Plan) AdvancementCalculator.compute(groups, totals, 1, 0);

        assertThat(plan.expectedCount()).isEqualTo(2);
        assertThat(plan.actualCount()).isEqualTo(2);
        assertThat(plan.entries())
                .allMatch(entry -> entry.type() == AdvanceType.DIRECT);
    }
}
