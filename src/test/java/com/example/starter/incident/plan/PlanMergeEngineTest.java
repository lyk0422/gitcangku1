package com.example.starter.incident.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.plan.PlanMergeEngine.ChangeKind;
import com.example.starter.incident.plan.PlanMergeEngine.Choice;
import com.example.starter.incident.plan.PlanMergeEngine.Conflict;
import com.example.starter.incident.plan.PlanMergeEngine.ConflictType;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeKey;
import com.example.starter.incident.plan.PlanMergeEngine.MergeDiff;
import com.example.starter.incident.plan.PlanMergeEngine.MergeOutcome;
import com.example.starter.incident.plan.PlanMergeEngine.Resolution;
import com.example.starter.incident.plan.PlanMergeEngine.Source;
import com.example.starter.incident.plan.PlanMergeEngine.TaskContent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 三方合并引擎纯单元测试：覆盖相同/单侧改动自动采用、双侧字段分歧、删改并存、
 * 双侧新增分歧、同任务对反向边冲突，以及解决的遗漏/多余/重复/MANUAL 校验。
 */
class PlanMergeEngineTest {

    private static TaskContent task(String taskId, String groupCode, String title,
                                    String assignee) {
        return new TaskContent(taskId, groupCode, title, assignee);
    }

    private static EdgeKey edge(String from, String to) {
        return new EdgeKey(from, EdgeKey.INTERNAL, to);
    }

    private static MergeDiff diff(List<TaskContent> base, List<TaskContent> left,
                                  List<TaskContent> right, Set<EdgeKey> baseEdges,
                                  Set<EdgeKey> leftEdges, Set<EdgeKey> rightEdges) {
        return PlanMergeEngine.diff(base, left, right, baseEdges, leftEdges, rightEdges);
    }

    private static void assertBadRequest(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void noChanges_keepsBase() {
        List<TaskContent> tasks = List.of(task("A", "G", "t", "u1"));
        Set<EdgeKey> edges = Set.of(edge("A", "B"));
        MergeDiff diff = diff(tasks, tasks, tasks, edges, edges, edges);
        assertThat(diff.changes()).isEmpty();
        assertThat(diff.conflicts()).isEmpty();
        MergeOutcome outcome = PlanMergeEngine.apply(diff, List.of());
        assertThat(outcome.tasks()).containsOnlyKeys("A");
        assertThat(outcome.tasks().get("A")).isEqualTo(task("A", "G", "t", "u1"));
        assertThat(outcome.edges()).containsExactly(edge("A", "B"));
    }

    @Test
    void singleSideChanges_autoAdopted() {
        List<TaskContent> base = List.of(task("A", "G", "t", "u1"), task("B", "G", "t", "u2"));
        List<TaskContent> left = List.of(task("A", "G", "左改", "u1"), task("B", "G", "t", "u2"),
                task("C", "G", "新增", "u3"));
        List<TaskContent> right = List.of(task("A", "G", "t", "u1"), task("B", "G", "t", "u9"));
        MergeDiff diff = diff(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).isEmpty();
        assertThat(diff.changes()).extracting(c -> c.kind() + ":" + c.source() + ":" + c.taskId())
                .containsExactlyInAnyOrder(
                        "TASK_MODIFIED:LEFT:A", "TASK_ADDED:LEFT:C", "TASK_MODIFIED:RIGHT:B");
        MergeOutcome outcome = PlanMergeEngine.apply(diff, List.of());
        assertThat(outcome.tasks().get("A").title()).isEqualTo("左改");
        assertThat(outcome.tasks().get("B").assignee()).isEqualTo("u9");
        assertThat(outcome.tasks().get("C").title()).isEqualTo("新增");
    }

    @Test
    void bothSidesSameChange_adoptedOnce() {
        List<TaskContent> base = List.of(task("A", "G", "t", "u1"));
        List<TaskContent> changed = List.of(task("A", "G", "同改", "u1"));
        MergeDiff diff = diff(base, changed, changed, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).isEmpty();
        assertThat(diff.changes()).singleElement().satisfies(c -> {
            assertThat(c.kind()).isEqualTo(ChangeKind.TASK_MODIFIED);
            assertThat(c.source()).isEqualTo(Source.BOTH);
        });
        assertThat(PlanMergeEngine.apply(diff, List.of()).tasks().get("A").title())
                .isEqualTo("同改");
    }

    @Test
    void fieldConflict_resolvedByLeftRightManual() {
        List<TaskContent> base = List.of(task("A", "G", "t", "u1"));
        List<TaskContent> left = List.of(task("A", "G", "左", "u1"));
        List<TaskContent> right = List.of(task("A", "G", "右", "u2"));
        MergeDiff diff = diff(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.conflictId()).isEqualTo("TASK:A");
            assertThat(c.type()).isEqualTo(ConflictType.TASK_FIELD_CONFLICT);
            assertThat(c.leftTask().title()).isEqualTo("左");
            assertThat(c.rightTask().title()).isEqualTo("右");
        });
        // LEFT
        MergeOutcome byLeft = PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.LEFT, null, null)));
        assertThat(byLeft.tasks().get("A").title()).isEqualTo("左");
        // RIGHT
        MergeOutcome byRight = PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.RIGHT, null, null)));
        assertThat(byRight.tasks().get("A").title()).isEqualTo("右");
        // MANUAL 需完整任务字段
        MergeOutcome manual = PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.MANUAL,
                        task("A", "G2", "手工", "u9"), null)));
        assertThat(manual.tasks().get("A").title()).isEqualTo("手工");
        assertThat(manual.tasks().get("A").assignee()).isEqualTo("u9");
        // MANUAL 缺任务字段 / taskId 不一致 → 400
        assertBadRequest(() -> PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.MANUAL, null, null))));
        assertBadRequest(() -> PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.MANUAL,
                        task("B", "G", "x", "u"), null))));
    }

    @Test
    void deleteModifyConflict() {
        List<TaskContent> base = List.of(task("A", "G", "t", "u1"), task("B", "G", "t", "u2"));
        // 左侧删除 A 且未改 B；右侧修改 A、删除 B（B 左侧未改 → 自动删）
        List<TaskContent> left = List.of(task("B", "G", "t", "u2"));
        List<TaskContent> right = List.of(task("A", "G", "右改", "u1"));
        MergeDiff diff = diff(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(ConflictType.TASK_DELETE_MODIFY);
            assertThat(c.taskId()).isEqualTo("A");
            assertThat(c.leftPresent()).isFalse();
            assertThat(c.rightPresent()).isTrue();
        });
        assertThat(diff.changes()).extracting(c -> c.kind() + ":" + c.taskId())
                .containsExactly("TASK_REMOVED:B");
        // 选 LEFT（删除侧）→ A 被移除；选 RIGHT（修改侧）→ A 保留为右侧内容
        MergeOutcome deleted = PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.LEFT, null, null)));
        assertThat(deleted.tasks()).doesNotContainKey("A");
        MergeOutcome kept = PlanMergeEngine.apply(diff,
                List.of(new Resolution("TASK:A", Choice.RIGHT, null, null)));
        assertThat(kept.tasks().get("A").title()).isEqualTo("右改");
    }

    @Test
    void addAddConflict() {
        List<TaskContent> left = List.of(task("N", "G", "左版", "u1"));
        List<TaskContent> right = List.of(task("N", "G", "右版", "u1"));
        MergeDiff diff = diff(List.of(), left, right, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).singleElement()
                .extracting(Conflict::type).isEqualTo(ConflictType.TASK_ADD_ADD);
        // 双侧新增同内容 → 自动采用，无冲突
        MergeDiff same = diff(List.of(), left, left, Set.of(), Set.of(), Set.of());
        assertThat(same.conflicts()).isEmpty();
        assertThat(PlanMergeEngine.apply(same, List.of()).tasks().get("N").title())
                .isEqualTo("左版");
    }

    @Test
    void edgeChanges_autoAndOppositeConflict() {
        // base 有 A→B；左侧删除 A→B、新增 B→C；右侧新增 C→A（与左侧无反向对）
        MergeDiff diff = diff(List.of(), List.of(), List.of(),
                Set.of(edge("A", "B")), Set.of(edge("B", "C")), Set.of(edge("A", "B"), edge("C", "A")));
        assertThat(diff.conflicts()).isEmpty();
        assertThat(diff.changes()).extracting(c -> c.kind() + ":" + c.source())
                .containsExactlyInAnyOrder("EDGE_REMOVED:LEFT", "EDGE_ADDED:LEFT",
                        "EDGE_ADDED:RIGHT");
        MergeOutcome outcome = PlanMergeEngine.apply(diff, List.of());
        assertThat(outcome.edges()).containsExactlyInAnyOrder(edge("B", "C"), edge("C", "A"));
    }

    @Test
    void edgeOppositeConflict_resolution() {
        // 左侧新增 A→B，右侧新增 B→A：同任务对反向边 → 显式冲突
        MergeDiff diff = diff(List.of(), List.of(), List.of(),
                Set.of(), Set.of(edge("A", "B")), Set.of(edge("B", "A")));
        assertThat(diff.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo(ConflictType.EDGE_OPPOSITE);
            assertThat(c.conflictId()).startsWith("EDGE:");
            assertThat(c.edge()).isEqualTo(edge("A", "B"));
            assertThat(c.reverseEdge()).isEqualTo(edge("B", "A"));
        });
        String conflictId = diff.conflicts().get(0).conflictId();
        MergeOutcome byLeft = PlanMergeEngine.apply(diff,
                List.of(new Resolution(conflictId, Choice.LEFT, null, null)));
        assertThat(byLeft.edges()).containsExactly(edge("A", "B"));
        MergeOutcome byRight = PlanMergeEngine.apply(diff,
                List.of(new Resolution(conflictId, Choice.RIGHT, null, null)));
        assertThat(byRight.edges()).containsExactly(edge("B", "A"));
        MergeOutcome manualKeep = PlanMergeEngine.apply(diff,
                List.of(new Resolution(conflictId, Choice.MANUAL, null, true)));
        assertThat(manualKeep.edges()).containsExactlyInAnyOrder(edge("A", "B"), edge("B", "A"));
        MergeOutcome manualDrop = PlanMergeEngine.apply(diff,
                List.of(new Resolution(conflictId, Choice.MANUAL, null, false)));
        assertThat(manualDrop.edges()).isEmpty();
        // MANUAL 缺边结果 → 400
        assertBadRequest(() -> PlanMergeEngine.apply(diff,
                List.of(new Resolution(conflictId, Choice.MANUAL, null, null))));
    }

    @Test
    void resolutionSetValidation() {
        List<TaskContent> base = List.of(task("A", "G", "t", "u1"));
        MergeDiff diff = diff(base, List.of(task("A", "G", "左", "u1")),
                List.of(task("A", "G", "右", "u1")), Set.of(), Set.of(), Set.of());
        // 遗漏
        assertBadRequest(() -> PlanMergeEngine.apply(diff, List.of()));
        // 多余（冲突不存在）
        assertBadRequest(() -> PlanMergeEngine.apply(diff, List.of(
                new Resolution("TASK:A", Choice.LEFT, null, null),
                new Resolution("TASK:X", Choice.LEFT, null, null))));
        // 重复
        assertBadRequest(() -> PlanMergeEngine.apply(diff, List.of(
                new Resolution("TASK:A", Choice.LEFT, null, null),
                new Resolution("TASK:A", Choice.RIGHT, null, null))));
        // 无冲突却给解决 → 多余
        MergeDiff noConflict = diff(base, base, base, Set.of(), Set.of(), Set.of());
        assertBadRequest(() -> PlanMergeEngine.apply(noConflict,
                List.of(new Resolution("TASK:A", Choice.LEFT, null, null))));
    }

    @Test
    void stableOrdering() {
        List<TaskContent> base = List.of(task("B", "G", "t", "u"), task("A", "G", "t", "u"));
        List<TaskContent> left = List.of(task("B", "G", "左", "u"), task("A", "G", "左", "u"));
        List<TaskContent> right = List.of(task("B", "G", "右", "u"), task("A", "G", "右", "u"));
        MergeDiff diff = diff(base, left, right, Set.of(), Set.of(), Set.of());
        assertThat(diff.conflicts()).extracting(Conflict::conflictId)
                .containsExactly("TASK:A", "TASK:B");
    }
}
