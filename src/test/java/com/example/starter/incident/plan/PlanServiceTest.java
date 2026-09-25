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
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.plan.dto.PlanRequests.DraftCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRemoveRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftEdgeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRemoveRequest;
import com.example.starter.incident.plan.dto.PlanRequests.DraftTaskRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeRequest;
import com.example.starter.incident.plan.dto.PlanRequests.MergeResolutionInput;
import com.example.starter.incident.plan.dto.PlanRequests.PlanCreateRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanEdgeInput;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskActionRequest;
import com.example.starter.incident.plan.dto.PlanRequests.PlanTaskInput;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeResultView;
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
 * 方案版本与三方合并服务测试（真实 H2）：覆盖首版发布、草稿分支编辑、三方合并
 * 主流程与冲突解决、完整图统一校验（环/悬空引用/跨事件规则）、运行时保护
 * （COMPLETED/IN_PROGRESS）、requestId 幂等与 mergeKey 唯一、失败回滚不占键。
 */
@SpringBootTest
class PlanServiceTest {

    @Autowired
    private PlanService plans;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM plan_merges");
        jdbc.update("DELETE FROM plan_task_state");
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

    private IncidentView commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static PlanTaskInput task(String taskId, String title, String assignee) {
        return new PlanTaskInput(taskId, "G", title, assignee);
    }

    private static PlanEdgeInput edge(String from, String to) {
        return new PlanEdgeInput(from, to, null);
    }

    private static PlanEdgeInput xedge(String from, String toIncidentKey, String to) {
        return new PlanEdgeInput(from, to, toIncidentKey);
    }

    private PlanVersionView createPlan(String incidentKey, String actor,
                                       List<PlanTaskInput> tasks, List<PlanEdgeInput> edges) {
        return plans.createInitialPlan(incidentKey, actor,
                new PlanCreateRequest(key(), tasks, edges));
    }

    private static MergeRequest mergeReq(String requestId, String mergeKey, int base, int left,
                                         int right, int leftExpected, int rightExpected,
                                         List<MergeResolutionInput> resolutions) {
        return new MergeRequest(requestId, mergeKey, base, left, right, leftExpected,
                rightExpected, resolutions);
    }

    private static MergeResolutionInput resolution(String conflictId, String choice) {
        return new MergeResolutionInput(conflictId, choice, null, null);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private int maxVersionNo(String incidentKey) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(version_no),0) FROM plan_versions v"
                        + " JOIN incidents i ON i.id = v.incident_id WHERE i.incident_key = ?",
                Integer.class, incidentKey);
    }

    // ---------- 首版创建 ----------

    @Test
    void initialPlan_createAndQuery() {
        commanding("INC-P1", "alice");
        PlanVersionView v1 = createPlan("INC-P1", "alice",
                List.of(task("A", "切换流量", "u1"), task("B", "扩容", "u2")),
                List.of(edge("A", "B")));
        assertThat(v1.versionNo()).isEqualTo(1);
        assertThat(v1.status()).isEqualTo("PUBLISHED");
        assertThat(v1.publishedAt()).isNotNull();
        assertThat(v1.tasks()).extracting(PlanTaskView::taskId).containsExactly("A", "B");
        assertThat(v1.tasks()).allSatisfy(t -> assertThat(t.status()).isEqualTo("PENDING"));
        assertThat(v1.edges()).singleElement().satisfies(e -> {
            assertThat(e.fromTaskId()).isEqualTo("A");
            assertThat(e.toTaskId()).isEqualTo("B");
            assertThat(e.toIncidentKey()).isEmpty();
        });
        // 活动版本与指定版本查询
        assertThat(plans.getActivePlan("INC-P1").versionNo()).isEqualTo(1);
        assertThat(plans.getVersion("INC-P1", 1).tasks()).hasSize(2);
        // 已存在版本后再次初始创建 → 409
        assertApiStatus(() -> createPlan("INC-P1", "alice", List.of(), List.of()),
                HttpStatus.CONFLICT);
        // 无版本事件查询活动版本 → 404
        commanding("INC-P2", "bob");
        assertApiStatus(() -> plans.getActivePlan("INC-P2"), HttpStatus.NOT_FOUND);
    }

    @Test
    void initialPlan_validation() {
        commanding("INC-V1", "alice");
        // 重复 taskId
        assertApiStatus(() -> createPlan("INC-V1", "alice",
                List.of(task("A", "t", "u"), task("A", "t2", "u")), List.of()),
                HttpStatus.BAD_REQUEST);
        // 边引用不存在任务
        assertApiStatus(() -> createPlan("INC-V1", "alice",
                List.of(task("A", "t", "u")), List.of(edge("A", "Z"))),
                HttpStatus.BAD_REQUEST);
        // 自依赖边
        assertApiStatus(() -> createPlan("INC-V1", "alice",
                List.of(task("A", "t", "u")), List.of(edge("A", "A"))),
                HttpStatus.BAD_REQUEST);
        // 环
        assertApiStatus(() -> createPlan("INC-V1", "alice",
                List.of(task("A", "t", "u"), task("B", "t", "u")),
                List.of(edge("A", "B"), edge("B", "A"))), HttpStatus.BAD_REQUEST);
        // 重复边
        assertApiStatus(() -> createPlan("INC-V1", "alice",
                List.of(task("A", "t", "u"), task("B", "t", "u")),
                List.of(edge("A", "B"), edge("A", "B"))), HttpStatus.BAD_REQUEST);
        // 非指挥人
        assertApiStatus(() -> createPlan("INC-V1", "bob", List.of(), List.of()),
                HttpStatus.CONFLICT);
        // 失败后无版本落库
        assertThat(maxVersionNo("INC-V1")).isZero();
    }

    @Test
    void initialPlan_closedIncident_422() {
        commanding("INC-Z1", "alice");
        incidents.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CONTAINED"));
        incidents.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "RESOLVED"));
        incidents.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> createPlan("INC-Z1", "alice", List.of(), List.of()),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ---------- 草稿分支 ----------

    @Test
    void draftBranching_editingRules() {
        commanding("INC-D1", "alice");
        createPlan("INC-D1", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")),
                List.of(edge("A", "B")));
        PlanVersionView left = plans.createDraft("INC-D1", "alice",
                new DraftCreateRequest(key(), 1, "LEFT"));
        PlanVersionView right = plans.createDraft("INC-D1", "alice",
                new DraftCreateRequest(key(), 1, "RIGHT"));
        assertThat(left.versionNo()).isEqualTo(2);
        assertThat(right.versionNo()).isEqualTo(3);
        assertThat(left.status()).isEqualTo("DRAFT");
        assertThat(left.baseVersionNo()).isEqualTo(1);
        assertThat(left.revision()).isZero();
        assertThat(left.tasks()).hasSize(2);
        assertThat(left.edges()).hasSize(1);

        // 编辑推进 revision；任务 upsert；移除任务级联删除内部边
        PlanVersionView edited = plans.upsertDraftTask("INC-D1", 2, "alice",
                new DraftTaskRequest(key(), "A", "G", "左改", "u1"));
        assertThat(edited.revision()).isEqualTo(1);
        assertThat(edited.tasks()).filteredOn(t -> t.taskId().equals("A"))
                .singleElement().extracting(PlanTaskView::title).isEqualTo("左改");
        PlanVersionView removed = plans.removeDraftTask("INC-D1", 2, "B", "alice",
                new DraftTaskRemoveRequest(key()));
        assertThat(removed.revision()).isEqualTo(2);
        assertThat(removed.tasks()).extracting(PlanTaskView::taskId).containsExactly("A");
        assertThat(removed.edges()).isEmpty();

        // 已发布版本不可编辑
        assertApiStatus(() -> plans.upsertDraftTask("INC-D1", 1, "alice",
                new DraftTaskRequest(key(), "A", "G", "x", "u")), HttpStatus.CONFLICT);
        // 边端点校验：任务不存在 404；重复边 409；移除不存在边 404
        assertApiStatus(() -> plans.addDraftEdge("INC-D1", 3, "alice",
                new DraftEdgeRequest(key(), "A", "Z", null)), HttpStatus.NOT_FOUND);
        plans.addDraftEdge("INC-D1", 3, "alice", new DraftEdgeRequest(key(), "B", "A", null));
        assertApiStatus(() -> plans.addDraftEdge("INC-D1", 3, "alice",
                new DraftEdgeRequest(key(), "B", "A", null)), HttpStatus.CONFLICT);
        assertApiStatus(() -> plans.removeDraftEdge("INC-D1", 3, "alice",
                new DraftEdgeRemoveRequest(key(), "Z", "A", null)), HttpStatus.NOT_FOUND);
        // branch 标记非法
        assertApiStatus(() -> plans.createDraft("INC-D1", "alice",
                new DraftCreateRequest(key(), 1, "MIDDLE")), HttpStatus.BAD_REQUEST);
        // 非指挥人编辑
        assertApiStatus(() -> plans.upsertDraftTask("INC-D1", 3, "bob",
                new DraftTaskRequest(key(), "A", "G", "x", "u")), HttpStatus.CONFLICT);
    }

    // ---------- 三方合并主流程 ----------

    /**
     * 构造 base v1：任务 A/B/C + 边 A→B；左支改 A 标题、删边 A→B；
     * 右支改 B 负责人；双侧对 C 标题分歧（冲突）。返回各版本号。
     */
    private void prepareConflictedBranches(String ik) {
        createPlan(ik, "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2"), task("C", "tc", "u3")),
                List.of(edge("A", "B")));
        plans.createDraft(ik, "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft(ik, "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        plans.upsertDraftTask(ik, 2, "alice", new DraftTaskRequest(key(), "A", "G", "左改", "u1"));
        plans.removeDraftEdge(ik, 2, "alice", new DraftEdgeRemoveRequest(key(), "A", "B", null));
        plans.upsertDraftTask(ik, 3, "alice", new DraftTaskRequest(key(), "B", "G", "tb", "u9"));
        plans.upsertDraftTask(ik, 2, "alice", new DraftTaskRequest(key(), "C", "G", "左C", "u3"));
        plans.upsertDraftTask(ik, 3, "alice", new DraftTaskRequest(key(), "C", "G", "右C", "u3"));
    }

    @Test
    void merge_mainFlow_withConflictResolution() {
        commanding("INC-M1", "alice");
        prepareConflictedBranches("INC-M1");
        // 差异查询：自动变更 + 显式冲突，稳定排序
        PlanDiffView diff = plans.diff("INC-M1", 1, 2, 3);
        assertThat(diff.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.conflictId()).isEqualTo("TASK:C");
            assertThat(c.type()).isEqualTo("TASK_FIELD_CONFLICT");
            assertThat(c.leftTask().title()).isEqualTo("左C");
            assertThat(c.rightTask().title()).isEqualTo("右C");
        });
        assertThat(diff.changes()).extracting(c -> c.kind() + ":" + c.source())
                .containsExactlyInAnyOrder("TASK_MODIFIED:LEFT", "TASK_MODIFIED:RIGHT",
                        "EDGE_REMOVED:LEFT");

        // 左支 revision=2（改 A、删边、改 C → 3 次？实际：改A、删边、改C = 3），右支 revision=2
        MergeResultView result = plans.merge("INC-M1", "alice",
                mergeReq("REQ-M1", "MK-M1", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "RIGHT"))));
        assertThat(result.resultVersionNo()).isEqualTo(4);
        assertThat(result.conflictsResolved()).isEqualTo(1);

        // 只生成一个新 PUBLISHED 版本；原分支 MERGED 不可变；base SUPERSEDED
        PlanVersionView v4 = plans.getActivePlan("INC-M1");
        assertThat(v4.versionNo()).isEqualTo(4);
        assertThat(v4.tasks()).extracting(PlanTaskView::taskId)
                .containsExactly("A", "B", "C");
        assertThat(v4.tasks()).filteredOn(t -> t.taskId().equals("A"))
                .singleElement().extracting(PlanTaskView::title).isEqualTo("左改");
        assertThat(v4.tasks()).filteredOn(t -> t.taskId().equals("B"))
                .singleElement().extracting(PlanTaskView::assignee).isEqualTo("u9");
        assertThat(v4.tasks()).filteredOn(t -> t.taskId().equals("C"))
                .singleElement().extracting(PlanTaskView::title).isEqualTo("右C");
        assertThat(v4.edges()).isEmpty();
        assertThat(plans.getVersion("INC-M1", 1).status()).isEqualTo("SUPERSEDED");
        assertThat(plans.getVersion("INC-M1", 2).status()).isEqualTo("MERGED");
        assertThat(plans.getVersion("INC-M1", 3).status()).isEqualTo("MERGED");
        // 已合并分支不可再编辑
        assertApiStatus(() -> plans.upsertDraftTask("INC-M1", 2, "alice",
                new DraftTaskRequest(key(), "A", "G", "x", "u")), HttpStatus.CONFLICT);
        // 运行时状态延续：任务仍 PENDING
        assertThat(v4.tasks()).allSatisfy(t -> assertThat(t.status()).isEqualTo("PENDING"));

        // 合并证据：冻结差异、解决、最终任务集与边集
        MergeEvidenceView evidence = plans.getMergeEvidence("INC-M1", "MK-M1");
        assertThat(evidence.requestId()).isEqualTo("REQ-M1");
        assertThat(evidence.baseVersion()).isEqualTo(1);
        assertThat(evidence.resultVersionNo()).isEqualTo(4);
        assertThat(evidence.diff().conflicts()).hasSize(1);
        assertThat(evidence.resolutions()).singleElement().satisfies(r -> {
            assertThat(r.conflictId()).isEqualTo("TASK:C");
            assertThat(r.choice()).isEqualTo("RIGHT");
        });
        assertThat(evidence.tasks()).extracting(PlanTaskView::taskId)
                .containsExactly("A", "B", "C");
        assertThat(evidence.edges()).isEmpty();
        assertThat(evidence.createdBy()).isEqualTo("alice");
    }

    @Test
    void merge_resolutionSetRejected_andFailureDoesNotConsumeRequestId() {
        commanding("INC-M2", "alice");
        prepareConflictedBranches("INC-M2");
        // 遗漏冲突解决 → 400
        assertApiStatus(() -> plans.merge("INC-M2", "alice",
                mergeReq("REQ-X", "MK-X", 1, 2, 3, 3, 2, List.of())), HttpStatus.BAD_REQUEST);
        // 多余解决 → 400
        assertApiStatus(() -> plans.merge("INC-M2", "alice",
                mergeReq("REQ-X", "MK-X", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "LEFT"), resolution("TASK:Z", "LEFT")))),
                HttpStatus.BAD_REQUEST);
        // 重复解决 → 400
        assertApiStatus(() -> plans.merge("INC-M2", "alice",
                mergeReq("REQ-X", "MK-X", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "LEFT"), resolution("TASK:C", "RIGHT")))),
                HttpStatus.BAD_REQUEST);
        // 失败不占键：修正后同 requestId 成功
        MergeResultView ok = plans.merge("INC-M2", "alice",
                mergeReq("REQ-X", "MK-X", 1, 2, 3, 3, 2, List.of(resolution("TASK:C", "LEFT"))));
        assertThat(ok.resultVersionNo()).isEqualTo(4);
        assertThat(plans.getActivePlan("INC-M2").tasks())
                .filteredOn(t -> t.taskId().equals("C"))
                .singleElement().extracting(PlanTaskView::title).isEqualTo("左C");
    }

    @Test
    void merge_revisionMismatch_409_andRetry() {
        commanding("INC-M3", "alice");
        prepareConflictedBranches("INC-M3");
        // 过期 expectedVersion → 409，且不生成版本、不占 requestId
        assertApiStatus(() -> plans.merge("INC-M3", "alice",
                mergeReq("REQ-R", "MK-R", 1, 2, 3, 1, 2,
                        List.of(resolution("TASK:C", "LEFT")))), HttpStatus.CONFLICT);
        assertThat(maxVersionNo("INC-M3")).isEqualTo(3);
        // 草稿被进一步编辑后，旧快照同样 409
        plans.upsertDraftTask("INC-M3", 2, "alice",
                new DraftTaskRequest(key(), "A", "G", "又改", "u1"));
        assertApiStatus(() -> plans.merge("INC-M3", "alice",
                mergeReq("REQ-R", "MK-R", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "LEFT")))), HttpStatus.CONFLICT);
        // 对齐最新 revision 后同 requestId 成功（失败不占键）
        MergeResultView ok = plans.merge("INC-M3", "alice",
                mergeReq("REQ-R", "MK-R", 1, 2, 3, 4, 2,
                        List.of(resolution("TASK:C", "LEFT"))));
        assertThat(ok.resultVersionNo()).isEqualTo(4);
    }

    @Test
    void merge_activeVersionMoved_409() {
        commanding("INC-M4", "alice");
        createPlan("INC-M4", "alice", List.of(task("A", "ta", "u1")), List.of());
        plans.createDraft("INC-M4", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M4", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        plans.createDraft("INC-M4", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M4", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 第一对合并成功：v6 发布（v2~v5 为四个草稿）
        MergeResultView first = plans.merge("INC-M4", "alice",
                mergeReq("REQ-1", "MK-1", 1, 2, 3, 0, 0, List.of()));
        assertThat(first.resultVersionNo()).isEqualTo(6);
        // 第二对仍以 v1 为 base：活动版本已前进 → 409
        assertApiStatus(() -> plans.merge("INC-M4", "alice",
                mergeReq("REQ-2", "MK-2", 1, 4, 5, 0, 0, List.of())), HttpStatus.CONFLICT);
        assertThat(maxVersionNo("INC-M4")).isEqualTo(6);
    }

    @Test
    void merge_cycleRejected_422_atomically() {
        commanding("INC-M5", "alice");
        createPlan("INC-M5", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")), List.of());
        plans.createDraft("INC-M5", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M5", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 左支加 A→B，右支加 B→A：同任务对反向边 → EDGE_OPPOSITE 冲突
        plans.addDraftEdge("INC-M5", 2, "alice", new DraftEdgeRequest(key(), "A", "B", null));
        plans.addDraftEdge("INC-M5", 3, "alice", new DraftEdgeRequest(key(), "B", "A", null));
        PlanDiffView diff = plans.diff("INC-M5", 1, 2, 3);
        assertThat(diff.conflicts()).singleElement()
                .extracting(c -> c.type()).isEqualTo("EDGE_OPPOSITE");
        String conflictId = diff.conflicts().get(0).conflictId();
        // MANUAL 保留双向边 → 合并后态成环 → 422，不生成版本、分支仍 DRAFT、不占键
        assertApiStatus(() -> plans.merge("INC-M5", "alice",
                mergeReq("REQ-C", "MK-C", 1, 2, 3, 1, 1,
                        List.of(new MergeResolutionInput(conflictId, "MANUAL", null, true)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(maxVersionNo("INC-M5")).isEqualTo(3);
        assertThat(plans.getVersion("INC-M5", 2).status()).isEqualTo("DRAFT");
        // 改为 MANUAL 丢弃双向边 → 成功
        MergeResultView ok = plans.merge("INC-M5", "alice",
                mergeReq("REQ-C", "MK-C", 1, 2, 3, 1, 1,
                        List.of(new MergeResolutionInput(conflictId, "MANUAL", null, false))));
        assertThat(ok.resultVersionNo()).isEqualTo(4);
        assertThat(plans.getActivePlan("INC-M5").edges()).isEmpty();
    }

    @Test
    void merge_danglingEdgeRejected_422() {
        commanding("INC-M6", "alice");
        createPlan("INC-M6", "alice",
                List.of(task("A", "ta", "u1"), task("X", "tx", "u2")), List.of());
        plans.createDraft("INC-M6", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M6", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 左支新增边 A→X；右支删除任务 X（左支未改 X → 自动删）→ 边悬空 → 422
        plans.addDraftEdge("INC-M6", 2, "alice", new DraftEdgeRequest(key(), "A", "X", null));
        plans.removeDraftTask("INC-M6", 3, "X", "alice", new DraftTaskRemoveRequest(key()));
        assertApiStatus(() -> plans.merge("INC-M6", "alice",
                mergeReq("REQ-D", "MK-D", 1, 2, 3, 1, 1, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(maxVersionNo("INC-M6")).isEqualTo(3);
    }

    // ---------- 运行时保护 ----------

    @Test
    void merge_completedTaskNotRemovable_422() {
        commanding("INC-M7", "alice");
        createPlan("INC-M7", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")), List.of());
        plans.startTask("INC-M7", "A", "alice", new PlanTaskActionRequest(key()));
        plans.completeTask("INC-M7", "A", "alice", new PlanTaskActionRequest(key()));
        plans.createDraft("INC-M7", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M7", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 右支删除已 COMPLETED 的 A（左支未改 → 自动删）→ 完成事实不可回退 → 422
        plans.removeDraftTask("INC-M7", 3, "A", "alice", new DraftTaskRemoveRequest(key()));
        assertApiStatus(() -> plans.merge("INC-M7", "alice",
                mergeReq("REQ-G", "MK-G", 1, 2, 3, 0, 1, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(maxVersionNo("INC-M7")).isEqualTo(3);
        // 完成事实仍在
        assertThat(plans.getActivePlan("INC-M7").tasks())
                .filteredOn(t -> t.taskId().equals("A"))
                .singleElement().extracting(PlanTaskView::status).isEqualTo("COMPLETED");
    }

    @Test
    void merge_inProgressGuards_422() {
        commanding("INC-M8", "alice");
        createPlan("INC-M8", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")), List.of());
        plans.startTask("INC-M8", "B", "alice", new PlanTaskActionRequest(key()));
        plans.createDraft("INC-M8", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-M8", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 左支更换执行中任务 B 的负责人 → 422
        plans.upsertDraftTask("INC-M8", 2, "alice",
                new DraftTaskRequest(key(), "B", "G", "tb", "u9"));
        assertApiStatus(() -> plans.merge("INC-M8", "alice",
                mergeReq("REQ-H", "MK-H", 1, 2, 3, 1, 0, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 改回负责人，但新增未满足前置 A→B（A 仍 PENDING）→ 422
        plans.upsertDraftTask("INC-M8", 2, "alice",
                new DraftTaskRequest(key(), "B", "G", "tb", "u2"));
        plans.addDraftEdge("INC-M8", 2, "alice", new DraftEdgeRequest(key(), "A", "B", null));
        assertApiStatus(() -> plans.merge("INC-M8", "alice",
                mergeReq("REQ-H", "MK-H", 1, 2, 3, 3, 0, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(maxVersionNo("INC-M8")).isEqualTo(3);
        // A 完成后，新增已满足前置 A→B → 允许
        plans.startTask("INC-M8", "A", "alice", new PlanTaskActionRequest(key()));
        plans.completeTask("INC-M8", "A", "alice", new PlanTaskActionRequest(key()));
        MergeResultView ok = plans.merge("INC-M8", "alice",
                mergeReq("REQ-H", "MK-H", 1, 2, 3, 3, 0, List.of()));
        assertThat(ok.resultVersionNo()).isEqualTo(4);
        assertThat(plans.getActivePlan("INC-M8").edges()).hasSize(1);
    }

    // ---------- 跨事件边 ----------

    @Test
    void crossIncidentEdges_gateAndRules() {
        commanding("INC-X", "alice");
        commanding("INC-Y", "alice");
        createPlan("INC-X", "alice", List.of(task("X1", "tx", "u1")), List.of());
        // 跨事件边：Y1 → INC-X:X1（X1 依赖 Y1）
        createPlan("INC-Y", "alice", List.of(task("Y1", "ty", "u2")),
                List.of(xedge("Y1", "INC-X", "X1")));
        // X1 的前置 Y1 未完成 → 启动 409，details 含跨事件前置
        assertThatThrownBy(() -> plans.startTask("INC-X", "X1", "alice",
                new PlanTaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<Object>) e.details()).containsExactly("INC-Y:Y1");
                });
        plans.startTask("INC-Y", "Y1", "alice", new PlanTaskActionRequest(key()));
        plans.completeTask("INC-Y", "Y1", "alice", new PlanTaskActionRequest(key()));
        PlanTaskView started = plans.startTask("INC-X", "X1", "alice",
                new PlanTaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");

        // 草稿跨事件边规则：目标事件不存在 404；目标任务不存在 409
        plans.createDraft("INC-X", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        assertApiStatus(() -> plans.addDraftEdge("INC-X", 2, "alice",
                new DraftEdgeRequest(key(), "X1", "X1", "INC-404")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> plans.addDraftEdge("INC-X", 2, "alice",
                new DraftEdgeRequest(key(), "X1", "ZZ", "INC-Y")), HttpStatus.CONFLICT);
        // 目标为自身 → 400
        assertApiStatus(() -> plans.addDraftEdge("INC-X", 2, "alice",
                new DraftEdgeRequest(key(), "X1", "X1", "INC-X")), HttpStatus.BAD_REQUEST);
    }

    @Test
    void crossIncident_globalCycle_422() {
        commanding("INC-XC", "alice");
        commanding("INC-YC", "alice");
        createPlan("INC-XC", "alice", List.of(task("X1", "tx", "u1")), List.of());
        createPlan("INC-YC", "alice", List.of(task("Y1", "ty", "u2")),
                List.of(xedge("Y1", "INC-XC", "X1")));
        // INC-XC 左支新增 X1 → INC-YC:Y1，与已有 Y1 → INC-XC:X1 构成跨事件环 → 422
        plans.createDraft("INC-XC", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-XC", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        plans.addDraftEdge("INC-XC", 2, "alice",
                new DraftEdgeRequest(key(), "X1", "Y1", "INC-YC"));
        assertApiStatus(() -> plans.merge("INC-XC", "alice",
                mergeReq("REQ-GC", "MK-GC", 1, 2, 3, 1, 0, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(maxVersionNo("INC-XC")).isEqualTo(3);
    }

    // ---------- 幂等 ----------

    @Test
    void merge_requestIdIdempotency() {
        commanding("INC-I1", "alice");
        prepareConflictedBranches("INC-I1");
        MergeRequest req = mergeReq("REQ-I", "MK-I", 1, 2, 3, 3, 2,
                List.of(resolution("TASK:C", "RIGHT")));
        MergeResultView first = plans.merge("INC-I1", "alice", req);
        // 同参重放首次快照
        MergeResultView replay = plans.merge("INC-I1", "alice", req);
        assertThat(replay).isEqualTo(first);
        assertThat(maxVersionNo("INC-I1")).isEqualTo(4);
        Integer mergeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges WHERE request_id = 'REQ-I'", Integer.class);
        assertThat(mergeRows).isEqualTo(1);
        // 异参（改解决选择）→ 409
        assertApiStatus(() -> plans.merge("INC-I1", "alice",
                mergeReq("REQ-I", "MK-I", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "LEFT")))), HttpStatus.CONFLICT);
        // mergeKey 唯一：基于新版本的新分支合并复用同一 mergeKey → 409，且不生成版本
        plans.createDraft("INC-I1", "alice", new DraftCreateRequest(key(), 4, "LEFT"));
        plans.createDraft("INC-I1", "alice", new DraftCreateRequest(key(), 4, "RIGHT"));
        assertApiStatus(() -> plans.merge("INC-I1", "alice",
                mergeReq("REQ-I2", "MK-I", 4, 5, 6, 0, 0, List.of())), HttpStatus.CONFLICT);
        assertThat(maxVersionNo("INC-I1")).isEqualTo(6);
    }

    @Test
    void merge_resolutionOrderEquivalent() {
        commanding("INC-I2", "alice");
        createPlan("INC-I2", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")), List.of());
        plans.createDraft("INC-I2", "alice", new DraftCreateRequest(key(), 1, "LEFT"));
        plans.createDraft("INC-I2", "alice", new DraftCreateRequest(key(), 1, "RIGHT"));
        // 两个任务双侧分歧 → 两个冲突
        plans.upsertDraftTask("INC-I2", 2, "alice",
                new DraftTaskRequest(key(), "A", "G", "左A", "u1"));
        plans.upsertDraftTask("INC-I2", 3, "alice",
                new DraftTaskRequest(key(), "A", "G", "右A", "u1"));
        plans.upsertDraftTask("INC-I2", 2, "alice",
                new DraftTaskRequest(key(), "B", "G", "左B", "u2"));
        plans.upsertDraftTask("INC-I2", 3, "alice",
                new DraftTaskRequest(key(), "B", "G", "右B", "u2"));
        MergeResultView first = plans.merge("INC-I2", "alice",
                mergeReq("REQ-O", "MK-O", 1, 2, 3, 2, 2,
                        List.of(resolution("TASK:A", "LEFT"), resolution("TASK:B", "RIGHT"))));
        // 冲突项换序 → 同参等价，重放首次快照
        MergeResultView replay = plans.merge("INC-I2", "alice",
                mergeReq("REQ-O", "MK-O", 1, 2, 3, 2, 2,
                        List.of(resolution("TASK:B", "RIGHT"), resolution("TASK:A", "LEFT"))));
        assertThat(replay).isEqualTo(first);
        Integer mergeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_merges WHERE request_id = 'REQ-O'", Integer.class);
        assertThat(mergeRows).isEqualTo(1);
    }

    @Test
    void planCommandKey_idempotency() {
        commanding("INC-I3", "alice");
        String commandKey = key();
        PlanVersionView first = plans.createInitialPlan("INC-I3", "alice",
                new PlanCreateRequest(commandKey, List.of(task("A", "ta", "u1")), List.of()));
        PlanVersionView replay = plans.createInitialPlan("INC-I3", "alice",
                new PlanCreateRequest(commandKey, List.of(task("A", "ta", "u1")), List.of()));
        assertThat(replay).isEqualTo(first);
        assertThat(maxVersionNo("INC-I3")).isEqualTo(1);
        // 同键改参 → 409
        assertApiStatus(() -> plans.createInitialPlan("INC-I3", "alice",
                new PlanCreateRequest(commandKey, List.of(task("B", "tb", "u1")), List.of())),
                HttpStatus.CONFLICT);
    }

    // ---------- 任务执行 ----------

    @Test
    void taskExecution_flow() {
        commanding("INC-E1", "alice");
        createPlan("INC-E1", "alice",
                List.of(task("A", "ta", "u1"), task("B", "tb", "u2")), List.of(edge("A", "B")));
        // 前置未完成 → 启动 409
        assertThatThrownBy(() -> plans.startTask("INC-E1", "B", "alice",
                new PlanTaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<Object>) e.details()).containsExactly("A");
                });
        PlanTaskView a = plans.startTask("INC-E1", "A", "alice", new PlanTaskActionRequest(key()));
        assertThat(a.status()).isEqualTo("IN_PROGRESS");
        assertThat(a.startedBy()).isEqualTo("alice");
        // 重复启动 → 409；完成前置后 B 可启动
        assertApiStatus(() -> plans.startTask("INC-E1", "A", "alice",
                new PlanTaskActionRequest(key())), HttpStatus.CONFLICT);
        PlanTaskView aDone = plans.completeTask("INC-E1", "A", "alice",
                new PlanTaskActionRequest(key()));
        assertThat(aDone.status()).isEqualTo("COMPLETED");
        assertThat(aDone.completedBy()).isEqualTo("alice");
        // 终态不可回退
        assertApiStatus(() -> plans.completeTask("INC-E1", "A", "alice",
                new PlanTaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> plans.startTask("INC-E1", "A", "alice",
                new PlanTaskActionRequest(key())), HttpStatus.CONFLICT);
        PlanTaskView b = plans.startTask("INC-E1", "B", "alice", new PlanTaskActionRequest(key()));
        assertThat(b.status()).isEqualTo("IN_PROGRESS");
        // 任务不存在 → 404；非指挥人 → 409
        assertApiStatus(() -> plans.startTask("INC-E1", "ZZ", "alice",
                new PlanTaskActionRequest(key())), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> plans.completeTask("INC-E1", "B", "bob",
                new PlanTaskActionRequest(key())), HttpStatus.CONFLICT);
        // commandKey 重放
        String completeKey = key();
        PlanTaskView bDone = plans.completeTask("INC-E1", "B", "alice",
                new PlanTaskActionRequest(completeKey));
        PlanTaskView bDoneReplay = plans.completeTask("INC-E1", "B", "alice",
                new PlanTaskActionRequest(completeKey));
        assertThat(bDoneReplay).isEqualTo(bDone);
    }

    // ---------- 查询 ----------

    @Test
    void evidenceAndDiffQueries_readOnlyStable() {
        commanding("INC-Q1", "alice");
        prepareConflictedBranches("INC-Q1");
        plans.merge("INC-Q1", "alice",
                mergeReq("REQ-Q", "MK-Q", 1, 2, 3, 3, 2,
                        List.of(resolution("TASK:C", "LEFT"))));
        // 未知 mergeKey → 404；其他事件的 mergeKey → 404
        assertApiStatus(() -> plans.getMergeEvidence("INC-Q1", "MK-404"), HttpStatus.NOT_FOUND);
        commanding("INC-Q2", "bob");
        assertApiStatus(() -> plans.getMergeEvidence("INC-Q2", "MK-Q"), HttpStatus.NOT_FOUND);
        // 差异查询只读且稳定排序
        PlanDiffView diff = plans.diff("INC-Q1", 1, 2, 3);
        assertThat(diff.changes()).extracting(c -> c.kind() + ":" + c.taskId())
                .containsExactly("TASK_MODIFIED:A", "TASK_MODIFIED:B", "EDGE_REMOVED:null");
        // 版本不存在 → 404
        assertApiStatus(() -> plans.getVersion("INC-Q1", 99), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> plans.diff("INC-Q1", 1, 2, 99), HttpStatus.NOT_FOUND);
    }
}
