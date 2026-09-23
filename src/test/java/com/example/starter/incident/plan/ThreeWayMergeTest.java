package com.example.starter.incident.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.plan.ThreeWayMerge.Choice;
import com.example.starter.incident.plan.ThreeWayMerge.ConflictType;
import com.example.starter.incident.plan.ThreeWayMerge.MergedPlan;
import com.example.starter.incident.plan.ThreeWayMerge.Outcome;
import com.example.starter.incident.plan.ThreeWayMerge.Resolution;
import com.example.starter.incident.plan.ThreeWayMerge.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 三方合并纯逻辑单元测试：自动采用、显式冲突生成、解决应用与图校验。
 * 不依赖数据库，直接验证 ThreeWayMerge 的比较与解决语义。
 */
class ThreeWayMergeTest {

    private static TaskContent task(String taskId, String title) {
        return new TaskContent(taskId, "INC-1", "G1", title, "alice",
                PlanTaskStatus.PENDING, null, null);
    }

    private static TaskContent task(String taskId, String title, String assignee) {
        return new TaskContent(taskId, "INC-1", "G1", title, assignee,
                PlanTaskStatus.PENDING, null, null);
    }

    private static Snapshot snapshot(Map<String, TaskContent> tasks, Set<EdgeKey> edges) {
        return new Snapshot(tasks, edges);
    }

    private static Map<String, TaskContent> tasksOf(TaskContent... tasks) {
        Map<String, TaskContent> map = new java.util.TreeMap<>();
        for (TaskContent task : tasks) {
            map.put(task.taskId(), task);
        }
        return map;
    }

    @Test
    void autoAdoptsUnchangedAndSingleSideChanges() {
        Snapshot base = snapshot(tasksOf(task("T-1", "v1"), task("T-2", "v1"), task("T-3", "v1")),
                Set.of(new EdgeKey("T-1", "T-2")));
        // 左侧：改 T-1、删 T-3、加 T-4、删边 T-1->T-2、加边 T-2->T-4
        Snapshot left = snapshot(tasksOf(task("T-1", "left"), task("T-2", "v1"), task("T-4", "new")),
                Set.of(new EdgeKey("T-2", "T-4")));
        // 右侧：仅改 T-2
        Snapshot right = snapshot(tasksOf(task("T-1", "v1"), task("T-2", "right"), task("T-3", "v1")),
                Set.of(new EdgeKey("T-1", "T-2")));

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).isEmpty();
        assertThat(outcome.edgeConflicts()).isEmpty();
        MergedPlan merged = ThreeWayMerge.apply(outcome, List.of());
        assertThat(merged.tasks().keySet()).containsExactly("T-1", "T-2", "T-4");
        assertThat(merged.tasks().get("T-1").title()).isEqualTo("left");
        assertThat(merged.tasks().get("T-2").title()).isEqualTo("right");
        assertThat(merged.edges()).containsExactly(new EdgeKey("T-2", "T-4"));
        // 差异项稳定排序且标注采用侧
        assertThat(outcome.items()).extracting(ThreeWayMerge.DiffItem::itemKey)
                .containsExactly("edge:T-1->T-2", "edge:T-2->T-4", "task:T-1", "task:T-2",
                        "task:T-3", "task:T-4");
        assertThat(outcome.items().stream()
                .filter(i -> i.itemKey().equals("task:T-1")).findFirst().orElseThrow().change())
                .isEqualTo("LEFT");
        assertThat(outcome.items().stream()
                .filter(i -> i.itemKey().equals("task:T-2")).findFirst().orElseThrow().change())
                .isEqualTo("RIGHT");
    }

    @Test
    void bothSidesSameChangeIsAutoAdopted() {
        Snapshot base = snapshot(tasksOf(task("T-1", "v1")), Set.of());
        Snapshot left = snapshot(tasksOf(task("T-1", "same"), task("T-2", "new")), Set.of());
        Snapshot right = snapshot(tasksOf(task("T-1", "same"), task("T-2", "new")), Set.of());

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).isEmpty();
        MergedPlan merged = ThreeWayMerge.apply(outcome, List.of());
        assertThat(merged.tasks().get("T-1").title()).isEqualTo("same");
        assertThat(merged.tasks()).containsKey("T-2");
        assertThat(outcome.items()).extracting(ThreeWayMerge.DiffItem::change)
                .containsOnly("BOTH");
    }

    @Test
    void fieldDivergenceGeneratesTaskFieldConflict() {
        Snapshot base = snapshot(tasksOf(task("T-1", "v1")), Set.of());
        Snapshot left = snapshot(tasksOf(task("T-1", "left")), Set.of());
        Snapshot right = snapshot(tasksOf(task("T-1", "right")), Set.of());

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).hasSize(1);
        ThreeWayMerge.TaskConflict conflict = outcome.taskConflicts().get(0);
        assertThat(conflict.conflictKey()).isEqualTo("task:T-1");
        assertThat(conflict.type()).isEqualTo(ConflictType.TASK_FIELD);
        assertThat(conflict.left().title()).isEqualTo("left");
        assertThat(conflict.right().title()).isEqualTo("right");

        // LEFT / RIGHT / MANUAL 三种解决
        MergedPlan byLeft = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("task:T-1", Choice.LEFT)));
        assertThat(byLeft.tasks().get("T-1").title()).isEqualTo("left");
        MergedPlan byRight = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("task:T-1", Choice.RIGHT)));
        assertThat(byRight.tasks().get("T-1").title()).isEqualTo("right");
        MergedPlan byManual = ThreeWayMerge.apply(outcome,
                List.of(Resolution.manualTask("task:T-1", task("T-1", "manual", "carol"))));
        assertThat(byManual.tasks().get("T-1").title()).isEqualTo("manual");
        assertThat(byManual.tasks().get("T-1").assignee()).isEqualTo("carol");
    }

    @Test
    void deleteVsModifyGeneratesDeleteModifyConflict() {
        Snapshot base = snapshot(tasksOf(task("T-1", "v1"), task("T-2", "v1")), Set.of());
        // 左侧删除 T-1，右侧修改 T-1
        Snapshot left = snapshot(tasksOf(task("T-2", "v1")), Set.of());
        Snapshot right = snapshot(tasksOf(task("T-1", "changed"), task("T-2", "v1")), Set.of());

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).hasSize(1);
        ThreeWayMerge.TaskConflict conflict = outcome.taskConflicts().get(0);
        assertThat(conflict.type()).isEqualTo(ConflictType.TASK_DELETE_MODIFY);
        assertThat(conflict.left()).isNull();
        assertThat(conflict.right().title()).isEqualTo("changed");

        // LEFT 采用删除：任务消失
        MergedPlan byLeft = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("task:T-1", Choice.LEFT)));
        assertThat(byLeft.tasks()).doesNotContainKey("T-1");
        // RIGHT 采用修改：任务保留为右侧内容
        MergedPlan byRight = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("task:T-1", Choice.RIGHT)));
        assertThat(byRight.tasks().get("T-1").title()).isEqualTo("changed");
    }

    @Test
    void bothAddSameTaskIdWithDifferentContentConflicts() {
        Snapshot base = snapshot(tasksOf(), Set.of());
        Snapshot left = snapshot(tasksOf(task("T-9", "from-left")), Set.of());
        Snapshot right = snapshot(tasksOf(task("T-9", "from-right")), Set.of());

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).hasSize(1);
        assertThat(outcome.taskConflicts().get(0).type()).isEqualTo(ConflictType.TASK_FIELD);
        assertThat(outcome.taskConflicts().get(0).base()).isNull();
    }

    @Test
    void oppositeDirectionEdgeAddsGenerateEdgeConflict() {
        Snapshot base = snapshot(tasksOf(task("A", "a"), task("B", "b")), Set.of());
        Snapshot left = snapshot(tasksOf(task("A", "a"), task("B", "b")),
                Set.of(new EdgeKey("A", "B")));
        Snapshot right = snapshot(tasksOf(task("A", "a"), task("B", "b")),
                Set.of(new EdgeKey("B", "A")));

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.edgeConflicts()).hasSize(1);
        ThreeWayMerge.EdgeConflict conflict = outcome.edgeConflicts().get(0);
        assertThat(conflict.type()).isEqualTo(ConflictType.EDGE_OPPOSITE);
        assertThat(conflict.conflictKey()).isEqualTo("edge:A->B");
        assertThat(conflict.leftEdge()).isEqualTo(new EdgeKey("A", "B"));
        assertThat(conflict.rightEdge()).isEqualTo(new EdgeKey("B", "A"));
        // 冲突边未进入自动采用
        assertThat(outcome.autoEdges()).isEmpty();

        MergedPlan byLeft = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("edge:A->B", Choice.LEFT)));
        assertThat(byLeft.edges()).containsExactly(new EdgeKey("A", "B"));
        MergedPlan byRight = ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("edge:A->B", Choice.RIGHT)));
        assertThat(byRight.edges()).containsExactly(new EdgeKey("B", "A"));
        // MANUAL 无边结果
        MergedPlan manualNone = ThreeWayMerge.apply(outcome,
                List.of(Resolution.manualEdge("edge:A->B", null)));
        assertThat(manualNone.edges()).isEmpty();
    }

    @Test
    void resolutionSetMustExactlyCoverConflicts() {
        Snapshot base = snapshot(tasksOf(task("T-1", "v1")), Set.of());
        Snapshot left = snapshot(tasksOf(task("T-1", "left")), Set.of());
        Snapshot right = snapshot(tasksOf(task("T-1", "right")), Set.of());
        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        // 遗漏
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome, List.of()))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("遗漏");
        // 多余
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome, List.of(
                Resolution.of("task:T-1", Choice.LEFT),
                Resolution.of("task:T-99", Choice.LEFT))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("多余");
        // 重复
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome, List.of(
                Resolution.of("task:T-1", Choice.LEFT),
                Resolution.of("task:T-1", Choice.RIGHT))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("重复");
        // MANUAL 缺少完整任务字段
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("task:T-1", Choice.MANUAL))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("MANUAL");
        // MANUAL taskId 与冲突不一致
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome,
                List.of(Resolution.manualTask("task:T-1", task("T-2", "x")))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("不一致");
    }

    @Test
    void manualEdgeResolutionRequiresEdgeResult() {
        Snapshot base = snapshot(tasksOf(task("A", "a"), task("B", "b")), Set.of());
        Snapshot left = snapshot(tasksOf(task("A", "a"), task("B", "b")),
                Set.of(new EdgeKey("A", "B")));
        Snapshot right = snapshot(tasksOf(task("A", "a"), task("B", "b")),
                Set.of(new EdgeKey("B", "A")));
        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        // MANUAL 但未给出边结果
        assertThatThrownBy(() -> ThreeWayMerge.apply(outcome,
                List.of(Resolution.of("edge:A->B", Choice.MANUAL))))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("边结果");
    }

    @Test
    void graphValidationRejectsDanglingEdgesAndCycles() {
        Map<String, TaskContent> tasks = tasksOf(task("A", "a"), task("B", "b"));
        // 引用不存在任务
        MergedPlan dangling = new MergedPlan(tasks, Set.of(new EdgeKey("A", "T-404")));
        assertThat(ThreeWayMerge.validateGraph(dangling)).contains("不存在的任务");
        // 自环
        MergedPlan selfLoop = new MergedPlan(tasks, Set.of(new EdgeKey("A", "A")));
        assertThat(ThreeWayMerge.validateGraph(selfLoop)).contains("环");
        // 间接环 A->B->C->A
        Map<String, TaskContent> three = tasksOf(task("A", "a"), task("B", "b"), task("C", "c"));
        MergedPlan cycle = new MergedPlan(three,
                Set.of(new EdgeKey("A", "B"), new EdgeKey("B", "C"), new EdgeKey("C", "A")));
        assertThat(ThreeWayMerge.validateGraph(cycle)).contains("环");
        // 合法 DAG
        MergedPlan dag = new MergedPlan(three,
                Set.of(new EdgeKey("A", "B"), new EdgeKey("B", "C"), new EdgeKey("A", "C")));
        assertThat(ThreeWayMerge.validateGraph(dag)).isNull();
    }

    @Test
    void completedFactsParticipateInComparison() {
        Instant doneAt = Instant.parse("2026-09-23T00:00:00Z");
        TaskContent done = new TaskContent("T-1", "INC-1", "G1", "t", "alice",
                PlanTaskStatus.COMPLETED, "alice", doneAt);
        Snapshot base = snapshot(tasksOf(done), Set.of());
        // 左侧回退为 PENDING（执行事实回退），右侧未动 → 自动采用左侧，由发布层 422 拦截
        Snapshot left = snapshot(tasksOf(task("T-1", "t")), Set.of());
        Snapshot right = snapshot(tasksOf(done), Set.of());

        Outcome outcome = ThreeWayMerge.merge(base, left, right);

        assertThat(outcome.taskConflicts()).isEmpty();
        MergedPlan merged = ThreeWayMerge.apply(outcome, List.of());
        assertThat(merged.tasks().get("T-1").status()).isEqualTo(PlanTaskStatus.PENDING);
    }
}
