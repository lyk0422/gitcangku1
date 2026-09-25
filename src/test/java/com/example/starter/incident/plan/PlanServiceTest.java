package com.example.starter.incident.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.IncidentService;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.plan.dto.PlanRequests.BranchCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.ManualEdge;
import com.example.starter.incident.plan.dto.PlanRequests.ManualTask;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeResolutionItem;
import com.example.starter.incident.plan.dto.PlanRequests.PlanEdgeInput;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskInput;
import com.example.starter.incident.plan.dto.PlanRequests.PlanVersionCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.TaskCompleteRequest;
import com.example.starter.incident.plan.dto.PlanRequests.TaskStartRequest;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanDiffView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 方案分支与三方合并服务测试（真实 H2）：覆盖初始发布、分支与草稿编辑、
 * 三方合并主流程与冲突解决、后态校验（成环/悬空引用/完成事实回退/
 * 执行中负责人与前置依赖）、跨事件边、幂等与失败回滚。
 */
@SpringBootTest
class PlanServiceTest {

    @Autowired
    private PlanService service;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_task_executions");
        jdbc.update("DELETE FROM plan_edges");
        jdbc.update("DELETE FROM plan_tasks");
        jdbc.update("DELETE FROM plan_versions");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "方案合并场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static PlanTaskInput task(String taskId, String title, String assignee) {
        return new PlanTaskInput(taskId, title, assignee);
    }

    private static PlanEdgeInput edge(String from, String toIncident, String to) {
        return new PlanEdgeInput(from, toIncident, to);
    }

    private PlanVersionView initPlan(String incidentKey, String actor,
                                     List<PlanTaskInput> tasks, List<PlanEdgeInput> edges) {
        return service.createInitialVersion(incidentKey, actor,
                new PlanVersionCreateRequest(key(), tasks, edges));
    }

    private PlanVersionView branch(String incidentKey, String actor, long baseVersionId) {
        return service.createBranch(incidentKey, actor, baseVersionId,
                new BranchCreateRequest(key()));
    }

    private MergeView merge(String incidentKey, String actor, String requestId, String mergeKey,
                            long base, long left, long right, long leftExp, long rightExp,
                            List<MergeResolutionItem> resolutions) {
        return service.merge(incidentKey, actor,
                new MergeRequest(requestId, mergeKey, base, left, right, leftExp, rightExp,
                        resolutions));
    }

    private static MergeResolutionItem resolve(String conflictId, String choice) {
        return new MergeResolutionItem(conflictId, choice, null, null);
    }

    // ---------- 初始版本 ----------

    @Test
    void createInitialVersion_mainFlow() {
        commanding("INC-P1", "alice");
        PlanVersionView view = initPlan("INC-P1", "alice",
                List.of(task("A", "切断流量", "u1"), task("B", "扩容", "u2")),
                List.of(edge("B", null, "A")));

        assertThat(view.status()).isEqualTo("PUBLISHED");
        assertThat(view.versionNo()).isEqualTo(1);
        assertThat(view.tasks()).extracting(PlanTaskView::taskId).containsExactly("A", "B");
        assertThat(view.tasks().get(0).status()).isEqualTo("PENDING");
        assertThat(view.edges()).hasSize(1);
        assertThat(view.edges().get(0).fromTaskId()).isEqualTo("B");
        assertThat(view.edges().get(0).toIncidentKey()).isEqualTo("INC-P1");
        assertThat(view.edges().get(0).toTaskId()).isEqualTo("A");

        // 活动版本与指定版本查询一致
        assertThat(service.getActivePlan("INC-P1")).isEqualTo(view);
        assertThat(service.getVersion("INC-P1", view.id())).isEqualTo(view);
        // 重复创建初始版本：409
        assertApiStatus(() -> initPlan("INC-P1", "alice", List.of(), List.of()),
                HttpStatus.CONFLICT);
    }

    @Test
    void createInitialVersion_validation() {
        commanding("INC-V1", "alice");
        // 悬空内部边：400
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null)), List.of(edge("A", null, "Z"))),
                HttpStatus.BAD_REQUEST);
        // 自环边：400
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null)), List.of(edge("A", null, "A"))),
                HttpStatus.BAD_REQUEST);
        // 成环：409 且不留版本
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null), task("B", "b", null)),
                List.of(edge("A", null, "B"), edge("B", null, "A"))), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.getActivePlan("INC-V1"), HttpStatus.NOT_FOUND);
        // 非指挥人：409
        assertApiStatus(() -> initPlan("INC-V1", "bob", List.of(), List.of()),
                HttpStatus.CONFLICT);
        // 重复任务 id / 重复边：400
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null), task("A", "a2", null)), List.of()),
                HttpStatus.BAD_REQUEST);
        // 跨事件边：目标事件不存在 404；目标事件无活动方案 409
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null)), List.of(edge("A", "INC-404", "T"))),
                HttpStatus.NOT_FOUND);
        commanding("INC-V2", "bob");
        assertApiStatus(() -> initPlan("INC-V1", "alice",
                List.of(task("A", "a", null)), List.of(edge("A", "INC-V2", "T"))),
                HttpStatus.CONFLICT);
        // 跨事件边成功：目标任务在目标事件活动版本内
        initPlan("INC-V2", "bob", List.of(task("T", "t", null)), List.of());
        PlanVersionView view = initPlan("INC-V1", "alice",
                List.of(task("A", "a", null)), List.of(edge("A", "INC-V2", "T")));
        assertThat(view.edges().get(0).toIncidentKey()).isEqualTo("INC-V2");
    }

    @Test
    void createInitialVersion_closedIncident_illegalTransition() {
        commanding("INC-Z1", "alice");
        incidentService.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> initPlan("INC-Z1", "alice", List.of(), List.of()),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------- 分支与草稿编辑 ----------

    @Test
    void branch_createLimitAndEdit() {
        commanding("INC-B1", "alice");
        PlanVersionView base = initPlan("INC-B1", "alice",
                List.of(task("A", "a", "u1"), task("B", "b", null)),
                List.of(edge("B", null, "A")));

        PlanVersionView left = branch("INC-B1", "alice", base.id());
        assertThat(left.status()).isEqualTo("DRAFT");
        assertThat(left.branchSide()).isEqualTo("LEFT");
        assertThat(left.revision()).isEqualTo(1);
        assertThat(left.baseVersionId()).isEqualTo(base.id());
        // 分支复制任务与边
        assertThat(left.tasks()).extracting(PlanTaskView::taskId).containsExactly("A", "B");
        assertThat(left.edges()).hasSize(1);

        PlanVersionView right = branch("INC-B1", "alice", base.id());
        assertThat(right.branchSide()).isEqualTo("RIGHT");
        // 第三个分支：409
        assertApiStatus(() -> branch("INC-B1", "alice", base.id()), HttpStatus.CONFLICT);
        // 从 DRAFT 再分支：409
        assertApiStatus(() -> branch("INC-B1", "alice", left.id()), HttpStatus.CONFLICT);

        // 草稿编辑：新增任务、改任务、加边、删边、删任务级联删边，revision 递增
        PlanVersionView v2 = service.upsertDraftTask("INC-B1", "alice", left.id(), "C",
                new DraftTaskRequest("c", "u3"));
        assertThat(v2.revision()).isEqualTo(2);
        PlanVersionView v3 = service.upsertDraftTask("INC-B1", "alice", left.id(), "A",
                new DraftTaskRequest("a2", "u1"));
        assertThat(v3.revision()).isEqualTo(3);
        assertThat(v3.tasks()).filteredOn(t -> t.taskId().equals("A"))
                .singleElement().extracting(PlanTaskView::title).isEqualTo("a2");
        PlanVersionView v4 = service.addDraftEdge("INC-B1", "alice", left.id(),
                new DraftEdgeRequest("C", null, "A"));
        assertThat(v4.edges()).hasSize(2);
        PlanVersionView v5 = service.deleteDraftEdge("INC-B1", "alice", left.id(),
                new DraftEdgeRequest("C", null, "A"));
        assertThat(v5.edges()).hasSize(1);
        PlanVersionView v6 = service.deleteDraftTask("INC-B1", "alice", left.id(), "B");
        assertThat(v6.tasks()).extracting(PlanTaskView::taskId).containsExactly("A", "C");
        // 级联删除 B 相关边
        assertThat(v6.edges()).isEmpty();
        assertThat(v6.revision()).isEqualTo(6);
    }

    @Test
    void draftEdit_validation() {
        commanding("INC-D1", "alice");
        PlanVersionView base = initPlan("INC-D1", "alice",
                List.of(task("A", "a", null), task("B", "b", null)), List.of());
        PlanVersionView draft = branch("INC-D1", "alice", base.id());

        // 编辑非草稿（已发布版本）：409
        assertApiStatus(() -> service.upsertDraftTask("INC-D1", "alice", base.id(), "A",
                new DraftTaskRequest("x", null)), HttpStatus.CONFLICT);
        // 自环边：400；依赖方不存在：400；前置不存在：400
        assertApiStatus(() -> service.addDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("A", null, "A")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.addDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("Z", null, "A")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.addDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("A", null, "Z")), HttpStatus.BAD_REQUEST);
        // 草稿内成环：409
        service.addDraftEdge("INC-D1", "alice", draft.id(), new DraftEdgeRequest("A", null, "B"));
        assertApiStatus(() -> service.addDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("B", null, "A")), HttpStatus.CONFLICT);
        // 重复边：409
        assertApiStatus(() -> service.addDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("A", null, "B")), HttpStatus.CONFLICT);
        // 删除不存在的边/任务：404
        assertApiStatus(() -> service.deleteDraftEdge("INC-D1", "alice", draft.id(),
                new DraftEdgeRequest("B", null, "A")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.deleteDraftTask("INC-D1", "alice", draft.id(), "Z"),
                HttpStatus.NOT_FOUND);
        // 非指挥人编辑：409
        assertApiStatus(() -> service.upsertDraftTask("INC-D1", "bob", draft.id(), "A",
                new DraftTaskRequest("x", null)), HttpStatus.CONFLICT);
    }

    // ---------- 合并主流程 ----------

    @Test
    void merge_autoOnly_publishesAtomically() {
        commanding("INC-M1", "alice");
        PlanVersionView base = initPlan("INC-M1", "alice",
                List.of(task("A", "a", "u1"), task("B", "b", null)), List.of());
        PlanVersionView left = branch("INC-M1", "alice", base.id());
        PlanVersionView right = branch("INC-M1", "alice", base.id());
        // 左改 A 标题，右删 B 加 C：互不冲突，自动采用
        service.upsertDraftTask("INC-M1", "alice", left.id(), "A",
                new DraftTaskRequest("a-new", "u1"));
        service.deleteDraftTask("INC-M1", "alice", right.id(), "B");
        service.upsertDraftTask("INC-M1", "alice", right.id(), "C",
                new DraftTaskRequest("c", "u3"));

        MergeView merged = merge("INC-M1", "alice", key(), "MG-1", base.id(), left.id(),
                right.id(), 2, 3, List.of());
        assertThat(merged.status()).isEqualTo("PUBLISHED");
        assertThat(merged.resultVersionNo()).isEqualTo(4);
        assertThat(merged.resolvedConflictCount()).isZero();
        assertThat(merged.taskCount()).isEqualTo(2);
        assertThat(merged.mergeKey()).isEqualTo("MG-1");

        PlanVersionView active = service.getActivePlan("INC-M1");
        assertThat(active.id()).isEqualTo(merged.resultVersionId());
        assertThat(active.tasks()).extracting(PlanTaskView::taskId).containsExactly("A", "C");
        assertThat(active.tasks().get(0).title()).isEqualTo("a-new");
        assertThat(active.leftVersionId()).isEqualTo(left.id());
        assertThat(active.rightVersionId()).isEqualTo(right.id());
        // 原分支标记 MERGED 且不可再编辑
        assertThat(service.getVersion("INC-M1", left.id()).status()).isEqualTo("MERGED");
        assertThat(service.getVersion("INC-M1", right.id()).status()).isEqualTo("MERGED");
        assertApiStatus(() -> service.upsertDraftTask("INC-M1", "alice", left.id(), "X",
                new DraftTaskRequest("x", null)), HttpStatus.CONFLICT);
        // 基准版本仍为 PUBLISHED 但不再是活动版本
        assertThat(service.getVersion("INC-M1", base.id()).status()).isEqualTo("PUBLISHED");
        // 合并证据：冻结差异、解决、最终任务集与边集
        MergeEvidenceView evidence = service.getMergeEvidence("INC-M1", "MG-1");
        assertThat(evidence.baseVersionId()).isEqualTo(base.id());
        assertThat(evidence.resultVersionId()).isEqualTo(merged.resultVersionId());
        assertThat(evidence.finalTasks()).extracting(PlanTaskView::taskId)
                .containsExactly("A", "C");
        assertThat(evidence.diff().taskChanges()).extracting("action")
                .containsExactly("MODIFIED", "REMOVED", "ADDED");
        assertThat(evidence.resolutions()).isEmpty();
    }

    @Test
    void merge_withConflicts_resolvedAtomically() {
        commanding("INC-M2", "alice");
        PlanVersionView base = initPlan("INC-M2", "alice",
                List.of(task("A", "a", "u1"), task("B", "b", null),
                        task("X", "x", null), task("Y", "y", null)), List.of());
        PlanVersionView left = branch("INC-M2", "alice", base.id());
        PlanVersionView right = branch("INC-M2", "alice", base.id());
        // 字段分歧：A 两侧改不同标题；删改并存：左删 B、右改 B；反向边：左 X→Y、右 Y→X
        service.upsertDraftTask("INC-M2", "alice", left.id(), "A",
                new DraftTaskRequest("a-left", "u1"));
        service.upsertDraftTask("INC-M2", "alice", right.id(), "A",
                new DraftTaskRequest("a-right", "u1"));
        service.deleteDraftTask("INC-M2", "alice", left.id(), "B");
        service.upsertDraftTask("INC-M2", "alice", right.id(), "B",
                new DraftTaskRequest("b-new", "u2"));
        service.addDraftEdge("INC-M2", "alice", left.id(), new DraftEdgeRequest("X", null, "Y"));
        service.addDraftEdge("INC-M2", "alice", right.id(), new DraftEdgeRequest("Y", null, "X"));

        // 差异查询：三个显式冲突，稳定排序
        PlanDiffView diff = service.diff("INC-M2", base.id(), left.id(), right.id());
        assertThat(diff.conflicts()).hasSize(3);
        assertThat(diff.conflicts()).extracting("conflictId")
                .containsExactly("EDGE|X>INC-M2/Y|Y>INC-M2/X", "TASK|A", "TASK|B");
        assertThat(diff.conflicts().get(1).type()).isEqualTo("FIELD_DIVERGENCE");
        assertThat(diff.conflicts().get(2).type()).isEqualTo("DELETE_VS_MODIFY");
        assertThat(diff.conflicts().get(0).type()).isEqualTo("OPPOSITE_DIRECTION");

        // 一次提交全部解决：A 用 MANUAL，B 选 RIGHT，边选 LEFT
        MergeView merged = merge("INC-M2", "alice", key(), "MG-2", base.id(), left.id(),
                right.id(), 4, 4, List.of(
                        resolve("EDGE|X>INC-M2/Y|Y>INC-M2/X", "LEFT"),
                        new MergeResolutionItem("TASK|A", "MANUAL",
                                new ManualTask("a-manual", "u9"), null),
                        resolve("TASK|B", "RIGHT")));
        assertThat(merged.resolvedConflictCount()).isEqualTo(3);

        PlanVersionView active = service.getActivePlan("INC-M2");
        assertThat(active.tasks()).extracting(PlanTaskView::taskId)
                .containsExactly("A", "B", "X", "Y");
        assertThat(active.tasks().get(0).title()).isEqualTo("a-manual");
        assertThat(active.tasks().get(0).assignee()).isEqualTo("u9");
        assertThat(active.tasks().get(1).title()).isEqualTo("b-new");
        assertThat(active.edges()).hasSize(1);
        assertThat(active.edges().get(0).fromTaskId()).isEqualTo("X");
        assertThat(active.edges().get(0).toTaskId()).isEqualTo("Y");
        // 证据冻结全部解决项（按 conflictId 排序）
        MergeEvidenceView evidence = service.getMergeEvidence("INC-M2", "MG-2");
        assertThat(evidence.resolutions()).hasSize(3);
        assertThat(evidence.resolutions().get(0).conflictId()).startsWith("EDGE|");
        assertThat(evidence.diff().conflicts()).hasSize(3);
    }

    @Test
    void merge_resolutionErrors_rejectedAndKeyNotConsumed() {
        commanding("INC-M3", "alice");
        PlanVersionView base = initPlan("INC-M3", "alice",
                List.of(task("A", "a", null)), List.of());
        PlanVersionView left = branch("INC-M3", "alice", base.id());
        PlanVersionView right = branch("INC-M3", "alice", base.id());
        service.upsertDraftTask("INC-M3", "alice", left.id(), "A",
                new DraftTaskRequest("a-left", null));
        service.upsertDraftTask("INC-M3", "alice", right.id(), "A",
                new DraftTaskRequest("a-right", null));

        String requestId = key();
        // 遗漏解决：400
        assertApiStatus(() -> merge("INC-M3", "alice", requestId, "MG-3", base.id(), left.id(),
                right.id(), 2, 2, List.of()), HttpStatus.BAD_REQUEST);
        // 多余解决：400
        assertApiStatus(() -> merge("INC-M3", "alice", requestId, "MG-3", base.id(), left.id(),
                right.id(), 2, 2, List.of(resolve("TASK|A", "LEFT"),
                        resolve("TASK|Z", "LEFT"))), HttpStatus.BAD_REQUEST);
        // 重复解决：400
        assertApiStatus(() -> merge("INC-M3", "alice", requestId, "MG-3", base.id(), left.id(),
                right.id(), 2, 2, List.of(resolve("TASK|A", "LEFT"),
                        resolve("TASK|A", "RIGHT"))), HttpStatus.BAD_REQUEST);
        // 失败不占键：同一 requestId 修正后成功
        MergeView merged = merge("INC-M3", "alice", requestId, "MG-3", base.id(), left.id(),
                right.id(), 2, 2, List.of(resolve("TASK|A", "LEFT")));
        assertThat(merged.status()).isEqualTo("PUBLISHED");
        assertThat(service.getActivePlan("INC-M3").tasks().get(0).title()).isEqualTo("a-left");
    }

    @Test
    void merge_branchChangedOrActiveAdvanced_conflict() {
        commanding("INC-M4", "alice");
        PlanVersionView base = initPlan("INC-M4", "alice",
                List.of(task("A", "a", null)), List.of());
        PlanVersionView left = branch("INC-M4", "alice", base.id());
        PlanVersionView right = branch("INC-M4", "alice", base.id());
        service.upsertDraftTask("INC-M4", "alice", left.id(), "A",
                new DraftTaskRequest("a-left", null));

        // expectedVersion 过期（左分支 revision=2，提交 1）：409
        assertApiStatus(() -> merge("INC-M4", "alice", key(), "MG-4a", base.id(), left.id(),
                right.id(), 1, 1, List.of()), HttpStatus.CONFLICT);
        // 分支再次修改后旧快照同样失效
        service.upsertDraftTask("INC-M4", "alice", left.id(), "A",
                new DraftTaskRequest("a-left-2", null));
        assertApiStatus(() -> merge("INC-M4", "alice", key(), "MG-4b", base.id(), left.id(),
                right.id(), 2, 1, List.of()), HttpStatus.CONFLICT);
        // 正确快照成功
        MergeView merged = merge("INC-M4", "alice", key(), "MG-4c", base.id(), left.id(),
                right.id(), 3, 1, List.of());
        assertThat(merged.status()).isEqualTo("PUBLISHED");
        // 活动版本已前进：基于旧基准的合并 409
        PlanVersionView left2 = branch("INC-M4", "alice", merged.resultVersionId());
        PlanVersionView right2 = branch("INC-M4", "alice", merged.resultVersionId());
        assertApiStatus(() -> merge("INC-M4", "alice", key(), "MG-4d", base.id(), left2.id(),
                right2.id(), 1, 1, List.of()), HttpStatus.CONFLICT);
        // 基准不是 PUBLISHED（用 MERGED 分支当基准）：409
        assertApiStatus(() -> merge("INC-M4", "alice", key(), "MG-4e", left.id(), left2.id(),
                right2.id(), 1, 1, List.of()), HttpStatus.CONFLICT);
    }

    // ---------- 合并后态校验 ----------

    @Test
    void merge_cycleInMergedGraph_rejected() {
        commanding("INC-G1", "alice");
        PlanVersionView base = initPlan("INC-G1", "alice",
                List.of(task("X", "x", null), task("Y", "y", null), task("Z", "z", null)),
                List.of());
        PlanVersionView left = branch("INC-G1", "alice", base.id());
        PlanVersionView right = branch("INC-G1", "alice", base.id());
        // 左加 X→Y，右加 Y→Z、Z→X：合并后成环
        service.addDraftEdge("INC-G1", "alice", left.id(), new DraftEdgeRequest("X", null, "Y"));
        service.addDraftEdge("INC-G1", "alice", right.id(), new DraftEdgeRequest("Y", null, "Z"));
        service.addDraftEdge("INC-G1", "alice", right.id(), new DraftEdgeRequest("Z", null, "X"));

        String requestId = key();
        assertApiStatus(() -> merge("INC-G1", "alice", requestId, "MG-G1", base.id(), left.id(),
                right.id(), 2, 3, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);
        // 不生成合并版本，活动版本不变，分支仍可编辑
        assertThat(service.getActivePlan("INC-G1").id()).isEqualTo(base.id());
        assertThat(service.getVersion("INC-G1", left.id()).status()).isEqualTo("DRAFT");
        // 失败不占键：右分支删掉成环边后同键成功
        service.deleteDraftEdge("INC-G1", "alice", right.id(),
                new DraftEdgeRequest("Z", null, "X"));
        MergeView merged = merge("INC-G1", "alice", requestId, "MG-G1", base.id(), left.id(),
                right.id(), 2, 4, List.of());
        assertThat(merged.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void merge_danglingEdge_rejected() {
        commanding("INC-G2", "alice");
        PlanVersionView base = initPlan("INC-G2", "alice",
                List.of(task("T", "t", null), task("X", "x", null)), List.of());
        PlanVersionView left = branch("INC-G2", "alice", base.id());
        PlanVersionView right = branch("INC-G2", "alice", base.id());
        // 左加边 T→X，右删任务 T：合并后边引用不存在任务
        service.addDraftEdge("INC-G2", "alice", left.id(), new DraftEdgeRequest("T", null, "X"));
        service.deleteDraftTask("INC-G2", "alice", right.id(), "T");

        assertApiStatus(() -> merge("INC-G2", "alice", key(), "MG-G2", base.id(), left.id(),
                right.id(), 2, 2, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getActivePlan("INC-G2").id()).isEqualTo(base.id());
    }

    @Test
    void merge_completedTask_notRolledBack() {
        commanding("INC-E1", "alice");
        PlanVersionView base = initPlan("INC-E1", "alice",
                List.of(task("A", "a", "u1"), task("B", "b", null)), List.of());
        // 完成 A（完成事实写入执行态）
        service.startTask("INC-E1", "alice", "A", new TaskStartRequest(key()));
        service.completePlanTask("INC-E1", "alice", "A", new TaskCompleteRequest(key()));

        PlanVersionView left = branch("INC-E1", "alice", base.id());
        PlanVersionView right = branch("INC-E1", "alice", base.id());
        // 左删除已完成任务 A：合并 422，完成事实不可回退
        service.deleteDraftTask("INC-E1", "alice", left.id(), "A");
        assertApiStatus(() -> merge("INC-E1", "alice", key(), "MG-E1a", base.id(), left.id(),
                right.id(), 2, 1, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);

        // 改标题允许，完成状态与事实保留
        service.upsertDraftTask("INC-E1", "alice", left.id(), "A",
                new DraftTaskRequest("a-renamed", "u1"));
        MergeView merged = merge("INC-E1", "alice", key(), "MG-E1b", base.id(), left.id(),
                right.id(), 3, 1, List.of());
        PlanTaskView a = service.getActivePlan("INC-E1").tasks().stream()
                .filter(t -> t.taskId().equals("A")).findFirst().orElseThrow();
        assertThat(a.title()).isEqualTo("a-renamed");
        assertThat(a.status()).isEqualTo("COMPLETED");
        assertThat(a.completedBy()).isEqualTo("alice");
        assertThat(a.completedAt()).isNotNull();
        assertThat(merged.taskCount()).isEqualTo(2);
    }

    @Test
    void merge_inProgressTask_protected() {
        commanding("INC-E2", "alice");
        PlanVersionView base = initPlan("INC-E2", "alice",
                List.of(task("P", "p", "u1"), task("Q", "q", "u2")), List.of());
        service.startTask("INC-E2", "alice", "P", new TaskStartRequest(key()));

        // 场景1：左分支更换执行中任务负责人 → 422
        PlanVersionView left = branch("INC-E2", "alice", base.id());
        PlanVersionView right = branch("INC-E2", "alice", base.id());
        service.upsertDraftTask("INC-E2", "alice", left.id(), "P",
                new DraftTaskRequest("p", "u9"));
        assertApiStatus(() -> merge("INC-E2", "alice", key(), "MG-E2a", base.id(), left.id(),
                right.id(), 2, 1, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);

        // 场景2：左分支删除执行中任务 → 422（先撤销负责人变更）
        service.upsertDraftTask("INC-E2", "alice", left.id(), "P",
                new DraftTaskRequest("p", "u1"));
        service.deleteDraftTask("INC-E2", "alice", left.id(), "P");
        assertApiStatus(() -> merge("INC-E2", "alice", key(), "MG-E2b", base.id(), left.id(),
                right.id(), 4, 1, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);

        // 场景3：恢复 P 后左分支新增未满足前置依赖 P→Q（Q 未完成）→ 422
        service.upsertDraftTask("INC-E2", "alice", left.id(), "P",
                new DraftTaskRequest("p", "u1"));
        service.addDraftEdge("INC-E2", "alice", left.id(), new DraftEdgeRequest("P", null, "Q"));
        assertApiStatus(() -> merge("INC-E2", "alice", key(), "MG-E2c", base.id(), left.id(),
                right.id(), 6, 1, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);

        // Q 完成后该前置已满足：同一边合并成功
        service.startTask("INC-E2", "alice", "Q", new TaskStartRequest(key()));
        service.completePlanTask("INC-E2", "alice", "Q", new TaskCompleteRequest(key()));
        MergeView merged = merge("INC-E2", "alice", key(), "MG-E2d", base.id(), left.id(),
                right.id(), 6, 1, List.of());
        assertThat(merged.status()).isEqualTo("PUBLISHED");
        assertThat(service.getActivePlan("INC-E2").edges()).hasSize(1);
    }

    @Test
    void merge_crossIncidentEdge_validatedAtPublish() {
        commanding("INC-X1", "alice");
        commanding("INC-X2", "bob");
        PlanVersionView baseB = initPlan("INC-X2", "bob",
                List.of(task("TB", "tb", "u1")), List.of());
        PlanVersionView baseA = initPlan("INC-X1", "alice",
                List.of(task("TA", "ta", "u2")), List.of());

        PlanVersionView leftA = branch("INC-X1", "alice", baseA.id());
        PlanVersionView rightA = branch("INC-X1", "alice", baseA.id());
        // 左分支加跨事件边 TA → INC-X2/TB
        service.addDraftEdge("INC-X1", "alice", leftA.id(),
                new DraftEdgeRequest("TA", "INC-X2", "TB"));

        // INC-X2 自己的合并移除了 TB：A 的合并 422（跨事件边引用失效）
        PlanVersionView leftB = branch("INC-X2", "bob", baseB.id());
        PlanVersionView rightB = branch("INC-X2", "bob", baseB.id());
        service.deleteDraftTask("INC-X2", "bob", leftB.id(), "TB");
        merge("INC-X2", "bob", key(), "MG-X2", baseB.id(), leftB.id(), rightB.id(), 2, 1,
                List.of());

        assertApiStatus(() -> merge("INC-X1", "alice", key(), "MG-X1a", baseA.id(), leftA.id(),
                rightA.id(), 2, 1, List.of()), HttpStatus.UNPROCESSABLE_ENTITY);
        // A 的活动版本未变
        assertThat(service.getActivePlan("INC-X1").id()).isEqualTo(baseA.id());
    }

    // ---------- 幂等 ----------

    @Test
    void merge_idempotency_replayAndConflict() {
        commanding("INC-I1", "alice");
        PlanVersionView base = initPlan("INC-I1", "alice",
                List.of(task("A", "a", null), task("B", "b", null)), List.of());
        PlanVersionView left = branch("INC-I1", "alice", base.id());
        PlanVersionView right = branch("INC-I1", "alice", base.id());
        service.upsertDraftTask("INC-I1", "alice", left.id(), "A",
                new DraftTaskRequest("a-left", null));
        service.upsertDraftTask("INC-I1", "alice", right.id(), "A",
                new DraftTaskRequest("a-right", null));

        String requestId = key();
        List<MergeResolutionItem> resolutions = List.of(resolve("TASK|A", "RIGHT"));
        MergeView first = merge("INC-I1", "alice", requestId, "MG-I1", base.id(), left.id(),
                right.id(), 2, 2, resolutions);
        // 同 requestId 同参重放：返回首次快照，不重复建版本
        MergeView replay = merge("INC-I1", "alice", requestId, "MG-I1", base.id(), left.id(),
                right.id(), 2, 2, resolutions);
        assertThat(replay).isEqualTo(first);
        assertThat(versionsCount("INC-I1")).isEqualTo(4);
        // 冲突项换序等价：单冲突场景用同集合不同表达验证——补充一个双冲突场景见下方
        // 同 requestId 异参：409
        assertApiStatus(() -> merge("INC-I1", "alice", requestId, "MG-I1", base.id(), left.id(),
                right.id(), 2, 2, List.of(resolve("TASK|A", "LEFT"))), HttpStatus.CONFLICT);
        // 同 mergeKey 不同 requestId 同内容：返回首次证据
        MergeView sameContent = merge("INC-I1", "alice", key(), "MG-I1", base.id(), left.id(),
                right.id(), 2, 2, resolutions);
        assertThat(sameContent).isEqualTo(first);
        assertThat(versionsCount("INC-I1")).isEqualTo(4);
        // 同 mergeKey 不同内容：409
        assertApiStatus(() -> merge("INC-I1", "alice", key(), "MG-I1", base.id(), left.id(),
                right.id(), 2, 2, List.of(resolve("TASK|A", "LEFT"))), HttpStatus.CONFLICT);
    }

    @Test
    void merge_resolutionOrderInsensitive() {
        commanding("INC-I2", "alice");
        PlanVersionView base = initPlan("INC-I2", "alice",
                List.of(task("A", "a", null), task("B", "b", null)), List.of());
        PlanVersionView left = branch("INC-I2", "alice", base.id());
        PlanVersionView right = branch("INC-I2", "alice", base.id());
        service.upsertDraftTask("INC-I2", "alice", left.id(), "A",
                new DraftTaskRequest("a-left", null));
        service.upsertDraftTask("INC-I2", "alice", right.id(), "A",
                new DraftTaskRequest("a-right", null));
        service.upsertDraftTask("INC-I2", "alice", left.id(), "B",
                new DraftTaskRequest("b-left", null));
        service.upsertDraftTask("INC-I2", "alice", right.id(), "B",
                new DraftTaskRequest("b-right", null));

        String requestId = key();
        MergeView first = merge("INC-I2", "alice", requestId, "MG-I2", base.id(), left.id(),
                right.id(), 3, 3, List.of(resolve("TASK|A", "LEFT"), resolve("TASK|B", "RIGHT")));
        // 冲突项换序：等价重放首次快照
        MergeView reordered = merge("INC-I2", "alice", requestId, "MG-I2", base.id(), left.id(),
                right.id(), 3, 3, List.of(resolve("TASK|B", "RIGHT"), resolve("TASK|A", "LEFT")));
        assertThat(reordered).isEqualTo(first);
        assertThat(versionsCount("INC-I2")).isEqualTo(4);
    }

    @Test
    void branchAndCreate_requestIdIdempotency() {
        commanding("INC-I3", "alice");
        String createKey = key();
        PlanVersionCreateRequest createReq = new PlanVersionCreateRequest(createKey,
                List.of(task("A", "a", null)), List.of());
        PlanVersionView first = service.createInitialVersion("INC-I3", "alice", createReq);
        PlanVersionView replay = service.createInitialVersion("INC-I3", "alice", createReq);
        assertThat(replay).isEqualTo(first);
        // 同键异参：409
        assertApiStatus(() -> service.createInitialVersion("INC-I3", "alice",
                new PlanVersionCreateRequest(createKey, List.of(task("B", "b", null)),
                        List.of())), HttpStatus.CONFLICT);

        String branchKey = key();
        PlanVersionView branch1 = service.createBranch("INC-I3", "alice", first.id(),
                new BranchCreateRequest(branchKey));
        PlanVersionView branchReplay = service.createBranch("INC-I3", "alice", first.id(),
                new BranchCreateRequest(branchKey));
        assertThat(branchReplay).isEqualTo(branch1);
        assertThat(branchReplay.branchSide()).isEqualTo("LEFT");
    }

    // ---------- 任务执行 ----------

    @Test
    void taskExecution_flowAndGates() {
        commanding("INC-T1", "alice");
        initPlan("INC-T1", "alice",
                List.of(task("A", "a", "u1"), task("B", "b", null), task("C", "c", "u3")),
                List.of(edge("C", null, "A")));

        // 未指派负责人不能开始：409
        assertApiStatus(() -> service.startTask("INC-T1", "alice", "B",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);
        // 前置未完成不能开始：409，details 列出未满足前置
        assertThatThrownBy(() -> service.startTask("INC-T1", "alice", "C",
                new TaskStartRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<Object>) e.details()).containsExactly("INC-T1/A");
                });
        // 开始 A → IN_PROGRESS；重复开始 409
        PlanTaskView started = service.startTask("INC-T1", "alice", "A",
                new TaskStartRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(started.startedBy()).isEqualTo("alice");
        assertThat(started.startedAt()).isNotNull();
        assertApiStatus(() -> service.startTask("INC-T1", "alice", "A",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);
        // 完成 A → COMPLETED；重复完成 409
        PlanTaskView completed = service.completePlanTask("INC-T1", "alice", "A",
                new TaskCompleteRequest(key()));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.completedBy()).isEqualTo("alice");
        assertThat(completed.completedAt()).isNotNull();
        assertApiStatus(() -> service.completePlanTask("INC-T1", "alice", "A",
                new TaskCompleteRequest(key())), HttpStatus.CONFLICT);
        // 前置已满足：C 可以开始
        PlanTaskView cStarted = service.startTask("INC-T1", "alice", "C",
                new TaskStartRequest(key()));
        assertThat(cStarted.status()).isEqualTo("IN_PROGRESS");
        // 完成未开始的任务：409；任务不存在：404
        assertApiStatus(() -> service.completePlanTask("INC-T1", "alice", "B",
                new TaskCompleteRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.startTask("INC-T1", "alice", "Z",
                new TaskStartRequest(key())), HttpStatus.NOT_FOUND);
        // 非指挥人：409
        assertApiStatus(() -> service.startTask("INC-T1", "bob", "B",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void taskExecution_commandKeyIdempotency() {
        commanding("INC-T2", "alice");
        initPlan("INC-T2", "alice", List.of(task("A", "a", "u1")), List.of());
        String startKey = key();
        PlanTaskView first = service.startTask("INC-T2", "alice", "A",
                new TaskStartRequest(startKey));
        PlanTaskView replay = service.startTask("INC-T2", "alice", "A",
                new TaskStartRequest(startKey));
        assertThat(replay).isEqualTo(first);
        // 同键异参：409
        assertApiStatus(() -> service.completePlanTask("INC-T2", "alice", "A",
                new TaskCompleteRequest(startKey)), HttpStatus.CONFLICT);
        // 失败不占键：业务失败后同键可成功复用
        String failKey = key();
        assertApiStatus(() -> service.completePlanTask("INC-T2", "alice", "Z",
                new TaskCompleteRequest(failKey)), HttpStatus.NOT_FOUND);
        PlanTaskView done = service.completePlanTask("INC-T2", "alice", "A",
                new TaskCompleteRequest(failKey));
        assertThat(done.status()).isEqualTo("COMPLETED");
    }

    // ---------- 查询 ----------

    @Test
    void queries_notFound() {
        commanding("INC-Q1", "alice");
        assertApiStatus(() -> service.getActivePlan("INC-Q1"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.getActivePlan("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.getVersion("INC-Q1", 999), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.getMergeEvidence("INC-Q1", "MG-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.diff("INC-Q1", 1, 2, 3), HttpStatus.NOT_FOUND);
    }

    private int versionsCount(String incidentKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_versions WHERE incident_key = ?", Integer.class,
                incidentKey);
        return count == null ? 0 : count;
    }
}
