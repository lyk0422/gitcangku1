package com.example.starter.incident.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.plan.PlanMergeEngine.Choice;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeConflict;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeKey;
import com.example.starter.incident.plan.PlanMergeEngine.MergeOutcome;
import com.example.starter.incident.plan.PlanMergeEngine.Resolution;
import com.example.starter.incident.plan.PlanMergeEngine.ResolvedPlan;
import com.example.starter.incident.plan.PlanMergeEngine.TaskConflict;
import com.example.starter.incident.plan.PlanMergeEngine.TaskFields;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 三方合并引擎纯单元测试：覆盖自动采用规则、全部冲突类型、
 * 解决应用与遗漏/多余/重复解决的拒绝。
 */
class PlanMergeEngineTest {

    private static final String INC = "INC-1";

    private static TaskFields tf(String title, String assignee) {
        return new TaskFields(title, assignee);
    }

    private static EdgeKey edge(String from, String to) {
        return new EdgeKey(from, INC, to);
    }

    private static MergeOutcome compute(Map<String, TaskFields> base,
                                        Map<String, TaskFields> left,
                                        Map<String, TaskFields> right,
                                        Set<EdgeKey> baseEdges, Set<EdgeKey> leftEdges,
                                        Set<EdgeKey> rightEdges) {
        return PlanMergeEngine.compute(INC, base, left, right, baseEdges, leftEdges, rightEdges);
    }

    @Test
    void autoAdopt_oneSideChanges() {
        Map<String, TaskFields> base = Map.of(
                "A", tf("a", "u1"), "B", tf("b", null), "C", tf("c", "u3"));
        Map<String, TaskFields> left = Map.of(
                "A", tf("a2", "u1"), "B", tf("b", null), "C", tf("c", "u3"), "D", tf("d", null));
        Map<String, TaskFields> right = Map.of(
                "A", tf("a", "u1"), "B", tf("b2", "u2"));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());

        assertThat(outcome.conflicts()).isEmpty();
        // 左侧改 A、右侧改 B、左侧增 D、右侧删 C：全部自动采用
        assertThat(outcome.tasks()).containsEntry("A", tf("a2", "u1"))
                .containsEntry("B", tf("b2", "u2"))
                .containsEntry("D", tf("d", null))
                .doesNotContainKey("C");
        assertThat(outcome.taskChanges()).extracting("action")
                .containsExactly("MODIFIED", "MODIFIED", "REMOVED", "ADDED");
    }

    @Test
    void autoAdopt_identicalBothSides() {
        Map<String, TaskFields> base = Map.of("A", tf("a", "u1"), "B", tf("b", null));
        // 两侧相同修改 A、同时删除 B、同名同内容新增 C
        Map<String, TaskFields> left = Map.of("A", tf("ax", "u1"), "C", tf("c", "u3"));
        Map<String, TaskFields> right = Map.of("A", tf("ax", "u1"), "C", tf("c", "u3"));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());

        assertThat(outcome.conflicts()).isEmpty();
        assertThat(outcome.tasks()).containsOnly(
                Map.entry("A", tf("ax", "u1")), Map.entry("C", tf("c", "u3")));
    }

    @Test
    void conflict_fieldDivergence_resolvedByAllChoices() {
        Map<String, TaskFields> base = Map.of("A", tf("a", "u1"));
        Map<String, TaskFields> left = Map.of("A", tf("aL", "u2"));
        Map<String, TaskFields> right = Map.of("A", tf("aR", "u3"));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());

        assertThat(outcome.conflicts()).hasSize(1);
        TaskConflict conflict = (TaskConflict) outcome.conflicts().get(0);
        assertThat(conflict.conflictId()).isEqualTo("TASK|A");
        assertThat(conflict.type()).isEqualTo(PlanMergeEngine.ConflictType.FIELD_DIVERGENCE);

        ResolvedPlan byLeft = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.LEFT, null, null)));
        assertThat(byLeft.tasks()).containsEntry("A", tf("aL", "u2"));
        ResolvedPlan byRight = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.RIGHT, null, null)));
        assertThat(byRight.tasks()).containsEntry("A", tf("aR", "u3"));
        ResolvedPlan byManual = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.MANUAL, tf("am", "u9"), null)));
        assertThat(byManual.tasks()).containsEntry("A", tf("am", "u9"));
    }

    @Test
    void conflict_bothAddedDifferently() {
        Map<String, TaskFields> left = Map.of("N", tf("nL", "u1"));
        Map<String, TaskFields> right = Map.of("N", tf("nR", "u2"));
        MergeOutcome outcome = compute(Map.of(), left, right, Set.of(), Set.of(), Set.of());

        TaskConflict conflict = (TaskConflict) outcome.conflicts().get(0);
        assertThat(conflict.type()).isEqualTo(PlanMergeEngine.ConflictType.BOTH_ADDED);
        assertThat(conflict.base()).isNull();
        ResolvedPlan plan = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|N", Choice.RIGHT, null, null)));
        assertThat(plan.tasks()).containsEntry("N", tf("nR", "u2"));
    }

    @Test
    void conflict_deleteVsModify() {
        Map<String, TaskFields> base = Map.of("A", tf("a", "u1"));
        Map<String, TaskFields> left = Map.of();
        Map<String, TaskFields> right = Map.of("A", tf("a2", "u1"));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());

        TaskConflict conflict = (TaskConflict) outcome.conflicts().get(0);
        assertThat(conflict.type()).isEqualTo(PlanMergeEngine.ConflictType.DELETE_VS_MODIFY);
        assertThat(conflict.left()).isNull();
        // 选删除侧：任务不存在；选修改侧：保留修改；MANUAL：保留为给定字段
        ResolvedPlan deleted = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.LEFT, null, null)));
        assertThat(deleted.tasks()).isEmpty();
        ResolvedPlan kept = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.RIGHT, null, null)));
        assertThat(kept.tasks()).containsEntry("A", tf("a2", "u1"));
        ResolvedPlan manual = PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.MANUAL, tf("am", null), null)));
        assertThat(manual.tasks()).containsEntry("A", tf("am", null));
    }

    @Test
    void edges_autoAdopt() {
        EdgeKey ab = edge("A", "B");
        EdgeKey bc = edge("B", "C");
        EdgeKey cd = edge("C", "D");
        // base 有 A→B、B→C；左删 A→B、加 C→D；右不变
        MergeOutcome outcome = compute(Map.of(), Map.of(), Map.of(),
                Set.of(ab, bc), Set.of(bc, cd), Set.of(ab, bc));

        assertThat(outcome.conflicts()).isEmpty();
        assertThat(outcome.edges()).containsExactlyInAnyOrder(bc, cd);
        assertThat(outcome.edgeChanges()).hasSize(2);
    }

    @Test
    void edges_oppositeDirection_conflict() {
        EdgeKey ab = edge("A", "B");
        EdgeKey ba = edge("B", "A");
        MergeOutcome outcome = compute(Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(ab), Set.of(ba));

        assertThat(outcome.edges()).isEmpty();
        assertThat(outcome.conflicts()).hasSize(1);
        EdgeConflict conflict = (EdgeConflict) outcome.conflicts().get(0);
        assertThat(conflict.leftEdge()).isEqualTo(ab);
        assertThat(conflict.rightEdge()).isEqualTo(ba);
        assertThat(conflict.conflictId()).startsWith("EDGE|");

        // LEFT：A→B；RIGHT：B→A；MANUAL 给边：指定之一；MANUAL 空：无边
        ResolvedPlan left = PlanMergeEngine.apply(outcome,
                List.of(new Resolution(conflict.conflictId(), Choice.LEFT, null, null)));
        assertThat(left.edges()).containsExactly(ab);
        ResolvedPlan right = PlanMergeEngine.apply(outcome,
                List.of(new Resolution(conflict.conflictId(), Choice.RIGHT, null, null)));
        assertThat(right.edges()).containsExactly(ba);
        ResolvedPlan manualNone = PlanMergeEngine.apply(outcome,
                List.of(new Resolution(conflict.conflictId(), Choice.MANUAL, null, null)));
        assertThat(manualNone.edges()).isEmpty();
        ResolvedPlan manualEdge = PlanMergeEngine.apply(outcome,
                List.of(new Resolution(conflict.conflictId(), Choice.MANUAL, null, ba)));
        assertThat(manualEdge.edges()).containsExactly(ba);
    }

    @Test
    void edges_crossIncidentNotTreatedAsOpposite() {
        // 跨事件边不参与反向冲突识别（反向边只能存在于本事件方案内）
        EdgeKey cross = new EdgeKey("A", "INC-2", "X");
        MergeOutcome outcome = compute(Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(cross), Set.of());
        assertThat(outcome.conflicts()).isEmpty();
        assertThat(outcome.edges()).containsExactly(cross);
    }

    @Test
    void resolutions_missingExtraDuplicate_rejected() {
        Map<String, TaskFields> base = Map.of("A", tf("a", "u1"), "B", tf("b", null));
        Map<String, TaskFields> left = Map.of("A", tf("aL", "u1"), "B", tf("bL", null));
        Map<String, TaskFields> right = Map.of("A", tf("aR", "u1"), "B", tf("bR", null));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(outcome.conflicts()).hasSize(2);

        // 遗漏：只解决一个
        assertThatThrownBy(() -> PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.LEFT, null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 多余：解决不存在的冲突
        assertThatThrownBy(() -> PlanMergeEngine.apply(outcome, List.of(
                new Resolution("TASK|A", Choice.LEFT, null, null),
                new Resolution("TASK|B", Choice.LEFT, null, null),
                new Resolution("TASK|Z", Choice.LEFT, null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 重复：同一冲突解决两次
        assertThatThrownBy(() -> PlanMergeEngine.apply(outcome, List.of(
                new Resolution("TASK|A", Choice.LEFT, null, null),
                new Resolution("TASK|A", Choice.RIGHT, null, null),
                new Resolution("TASK|B", Choice.LEFT, null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        // 无冲突时提交解决项：多余
        MergeOutcome noConflict = compute(Map.of("A", tf("a", null)), Map.of("A", tf("a", null)),
                Map.of("A", tf("a", null)), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> PlanMergeEngine.apply(noConflict,
                List.of(new Resolution("TASK|A", Choice.LEFT, null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void manualValidation_rejected() {
        Map<String, TaskFields> base = Map.of("A", tf("a", "u1"));
        Map<String, TaskFields> left = Map.of("A", tf("aL", "u2"));
        Map<String, TaskFields> right = Map.of("A", tf("aR", "u3"));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());

        // MANUAL 缺完整任务字段
        assertThatThrownBy(() -> PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.MANUAL, null, null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> PlanMergeEngine.apply(outcome,
                List.of(new Resolution("TASK|A", Choice.MANUAL, tf(" ", "u"), null))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 边冲突 MANUAL 给出冲突对之外的边
        EdgeKey ab = edge("A", "B");
        EdgeKey ba = edge("B", "A");
        MergeOutcome edgeOutcome = compute(Map.of(), Map.of(), Map.of(),
                Set.of(), Set.of(ab), Set.of(ba));
        String conflictId = edgeOutcome.conflicts().get(0).conflictId();
        assertThatThrownBy(() -> PlanMergeEngine.apply(edgeOutcome,
                List.of(new Resolution(conflictId, Choice.MANUAL, null, edge("A", "C")))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void conflicts_stableOrdering() {
        Map<String, TaskFields> base = Map.of("B", tf("b", null), "A", tf("a", null));
        Map<String, TaskFields> left = Map.of("B", tf("bL", null), "A", tf("aL", null));
        Map<String, TaskFields> right = Map.of("B", tf("bR", null), "A", tf("aR", null));
        MergeOutcome outcome = compute(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(outcome.conflicts()).extracting("conflictId")
                .containsExactly("TASK|A", "TASK|B");
    }
}
