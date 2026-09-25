package com.example.starter.incident.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.CommandKeyRecord;
import com.example.starter.incident.CommandKeyRepository;
import com.example.starter.incident.Incident;
import com.example.starter.incident.IncidentRepository;
import com.example.starter.incident.IncidentStatus;
import com.example.starter.incident.IncidentTaskRepository;
import com.example.starter.incident.plan.PlanMergeEngine.Change;
import com.example.starter.incident.plan.PlanMergeEngine.Choice;
import com.example.starter.incident.plan.PlanMergeEngine.Conflict;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeKey;
import com.example.starter.incident.plan.PlanMergeEngine.MergeDiff;
import com.example.starter.incident.plan.PlanMergeEngine.MergeOutcome;
import com.example.starter.incident.plan.PlanMergeEngine.Resolution;
import com.example.starter.incident.plan.PlanMergeEngine.TaskContent;
import com.example.starter.incident.plan.PlanRepository.GraphEdge;
import com.example.starter.incident.plan.PlanRepository.GraphNode;
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
import com.example.starter.incident.plan.dto.PlanResponses.DiffChangeView;
import com.example.starter.incident.plan.dto.PlanResponses.DiffConflictView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeResolutionView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeResultView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanDiffView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanEdgeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置方案版本与三方合并核心服务。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行，同事务内完成
 * 幂等占位、校验与写入，与任务执行、依赖变更及另一合并按事务提交顺序生效；
 * 合并发布额外持有 task_graph_lock 全局图锁做跨事件环检测。
 * 幂等约定：commandKey/requestId 全局唯一，同参重放首次快照（合并请求的冲突解决
 * 按 conflictId 排序后参与哈希，换序等价），异参 409，失败事务回滚不占键；
 * mergeKey 全局唯一。
 */
@Service
public class PlanService {

    private static final String SEP = "\\u001F";

    private final IncidentRepository incidents;
    private final IncidentTaskRepository taskGraph;
    private final PlanRepository plans;
    private final PlanMergeRepository merges;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlanService(IncidentRepository incidents, IncidentTaskRepository taskGraph,
                       PlanRepository plans, PlanMergeRepository merges,
                       CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.taskGraph = taskGraph;
        this.plans = plans;
        this.merges = merges;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    // ---------- 首版创建并发布 ----------

    /**
     * 创建首个方案版本并直接发布（PUBLISHED，活动版本）。仅当前指挥人；
     * 事件 CLOSED 后禁止；已存在方案版本时 409。任务 taskId 版本内唯一；
     * 边引用必须存在、不得成环，跨事件边须满足既有权限与状态规则。
     */
    @Transactional
    public PlanVersionView createInitialPlan(String incidentKey, String actor,
                                             PlanCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        List<PlanTaskInput> taskInputs = req.tasks() == null ? List.of() : req.tasks();
        List<PlanEdgeInput> edgeInputs = req.edges() == null ? List.of() : req.edges();
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_create",
                hash(incidentKey, actor, toJson(taskInputs), toJson(edgeInputs)),
                PlanVersionView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    if (plans.maxVersionNo(incident.id()) > 0) {
                        throw ApiException.conflict("已存在方案版本，初始创建仅允许首个版本");
                    }
                    Map<String, TaskContent> tasks = parseTasks(taskInputs);
                    Set<EdgeKey> edges = parseEdges(edgeInputs);
                    validateGraph(incident, tasks, edges, true);
                    Instant now = now();
                    long versionId = plans.insertVersion(new PlanVersion(0L, incident.id(), 1,
                            PlanVersionStatus.PUBLISHED, null, null, 0, null, actor, now, now));
                    for (TaskContent task : tasks.values()) {
                        plans.insertTask(versionId, new PlanTask(0L, versionId, task.taskId(),
                                task.groupCode(), task.title(), task.assignee(), now));
                    }
                    for (EdgeKey edge : edges) {
                        plans.insertEdge(versionId, new PlanEdge(0L, versionId, edge.fromTaskId(),
                                edge.toIncidentKey(), edge.toTaskId(), now));
                    }
                    syncRuntime(incident.id(), tasks, now);
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), 1).orElseThrow());
                });
    }

    // ---------- 草稿分支 ----------

    /**
     * 从 PUBLISHED 基版本创建 DRAFT 修订（复制任务与边，revision 从 0 开始）。
     * 仅当前指挥人；基版本必须是当前活动版本，否则 409。
     */
    @Transactional
    public PlanVersionView createDraft(String incidentKey, String actor, DraftCreateRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        if (req.baseVersion() == null) {
            throw ApiException.badRequest("baseVersion 不能为空");
        }
        String branch = req.branch() == null ? null : req.branch().strip();
        if (branch != null && !branch.isEmpty()
                && !branch.equals("LEFT") && !branch.equals("RIGHT")) {
            throw ApiException.badRequest("branch 只能为 LEFT 或 RIGHT");
        }
        Incident incident = lockIncident(incidentKey);
        String finalBranch = branch == null || branch.isEmpty() ? null : branch;
        return runIdempotent(commandKey, "plan_draft_create",
                hash(incidentKey, actor, String.valueOf(req.baseVersion()),
                        String.valueOf(finalBranch)),
                PlanVersionView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion base = plans.findVersion(incident.id(), req.baseVersion())
                            .orElseThrow(() -> ApiException.notFound(
                                    "基版本不存在: " + req.baseVersion()));
                    if (base.status() != PlanVersionStatus.PUBLISHED) {
                        throw ApiException.conflict("只能从当前 PUBLISHED 活动版本创建草稿，"
                                + "版本 " + req.baseVersion() + " 状态为 " + base.status());
                    }
                    Instant now = now();
                    int versionNo = plans.maxVersionNo(incident.id()) + 1;
                    long draftId = plans.insertVersion(new PlanVersion(0L, incident.id(),
                            versionNo, PlanVersionStatus.DRAFT, base.id(), finalBranch, 0, null,
                            actor, now, null));
                    for (PlanTask task : plans.listTasks(base.id())) {
                        plans.insertTask(draftId, new PlanTask(0L, draftId, task.taskId(),
                                task.groupCode(), task.title(), task.assignee(), now));
                    }
                    for (PlanEdge edge : plans.listEdges(base.id())) {
                        plans.insertEdge(draftId, new PlanEdge(0L, draftId, edge.fromTaskId(),
                                edge.toIncidentKey(), edge.toTaskId(), now));
                    }
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), versionNo).orElseThrow());
                });
    }

    /**
     * 草稿任务新增/修改（按稳定 taskId upsert），revision +1。仅 DRAFT 可编辑。
     */
    @Transactional
    public PlanVersionView upsertDraftTask(String incidentKey, int versionNo, String actor,
                                           DraftTaskRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String taskId = requireText(req.taskId(), "taskId");
        String groupCode = requireText(req.groupCode(), "groupCode");
        String title = requireText(req.title(), "title");
        String assignee = requireText(req.assignee(), "assignee");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_draft_task_put",
                hash(incidentKey, actor, String.valueOf(versionNo), taskId, groupCode, title,
                        assignee),
                PlanVersionView.class, () -> {
                    PlanVersion draft = requireEditableDraft(incident, actor, versionNo);
                    PlanTask task = new PlanTask(0L, draft.id(), taskId, groupCode, title,
                            assignee, now());
                    if (plans.findTask(draft.id(), taskId).isPresent()) {
                        plans.updateTask(draft.id(), task);
                    } else {
                        plans.insertTask(draft.id(), task);
                    }
                    plans.bumpRevision(draft.id(), draft.revision() + 1);
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), versionNo).orElseThrow());
                });
    }

    /**
     * 草稿任务移除（连同其本事件内部关联边），revision +1。仅 DRAFT 可编辑。
     */
    @Transactional
    public PlanVersionView removeDraftTask(String incidentKey, int versionNo, String taskId,
                                           String actor, DraftTaskRemoveRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        requireText(taskId, "taskId");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_draft_task_del",
                hash(incidentKey, actor, String.valueOf(versionNo), taskId),
                PlanVersionView.class, () -> {
                    PlanVersion draft = requireEditableDraft(incident, actor, versionNo);
                    if (plans.findTask(draft.id(), taskId).isEmpty()) {
                        throw ApiException.notFound("草稿中任务不存在: " + taskId);
                    }
                    plans.deleteTask(draft.id(), taskId);
                    for (PlanEdge edge : plans.listEdges(draft.id())) {
                        boolean references = edge.fromTaskId().equals(taskId)
                                || (edge.toIncidentKey().isEmpty()
                                && edge.toTaskId().equals(taskId));
                        if (references) {
                            plans.deleteEdge(draft.id(), edge.fromTaskId(), edge.toIncidentKey(),
                                    edge.toTaskId());
                        }
                    }
                    plans.bumpRevision(draft.id(), draft.revision() + 1);
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), versionNo).orElseThrow());
                });
    }

    /**
     * 草稿边新增，revision +1。仅 DRAFT 可编辑；from 任务须存在于草稿；
     * 跨事件边目标事件须存在、非自身、非 CLOSED 且其活动版本含目标任务。
     * 环检测不在编辑时做，合并/发布时对完整图统一校验。
     */
    @Transactional
    public PlanVersionView addDraftEdge(String incidentKey, int versionNo, String actor,
                                        DraftEdgeRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String fromTaskId = requireText(req.fromTaskId(), "fromTaskId");
        String toTaskId = requireText(req.toTaskId(), "toTaskId");
        String toIncidentKey = normalizeToIncidentKey(req.toIncidentKey());
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_draft_edge_add",
                hash(incidentKey, actor, String.valueOf(versionNo), fromTaskId, toIncidentKey,
                        toTaskId),
                PlanVersionView.class, () -> {
                    PlanVersion draft = requireEditableDraft(incident, actor, versionNo);
                    validateEdgeEndpoints(incident, draft.id(), fromTaskId, toIncidentKey,
                            toTaskId);
                    EdgeKey edge = new EdgeKey(fromTaskId, toIncidentKey, toTaskId);
                    if (toEdgeKeys(plans.listEdges(draft.id())).contains(edge)) {
                        throw ApiException.conflict("边已存在: " + edge.id());
                    }
                    plans.insertEdge(draft.id(), new PlanEdge(0L, draft.id(), fromTaskId,
                            toIncidentKey, toTaskId, now()));
                    plans.bumpRevision(draft.id(), draft.revision() + 1);
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), versionNo).orElseThrow());
                });
    }

    /**
     * 草稿边移除，revision +1。仅 DRAFT 可编辑；边不存在 404。
     */
    @Transactional
    public PlanVersionView removeDraftEdge(String incidentKey, int versionNo, String actor,
                                           DraftEdgeRemoveRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String fromTaskId = requireText(req.fromTaskId(), "fromTaskId");
        String toTaskId = requireText(req.toTaskId(), "toTaskId");
        String toIncidentKey = normalizeToIncidentKey(req.toIncidentKey());
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_draft_edge_del",
                hash(incidentKey, actor, String.valueOf(versionNo), fromTaskId, toIncidentKey,
                        toTaskId),
                PlanVersionView.class, () -> {
                    PlanVersion draft = requireEditableDraft(incident, actor, versionNo);
                    EdgeKey edge = new EdgeKey(fromTaskId, toIncidentKey, toTaskId);
                    if (!toEdgeKeys(plans.listEdges(draft.id())).contains(edge)) {
                        throw ApiException.notFound("草稿中边不存在: " + edge.id());
                    }
                    plans.deleteEdge(draft.id(), fromTaskId, toIncidentKey, toTaskId);
                    plans.bumpRevision(draft.id(), draft.revision() + 1);
                    return toVersionView(incident.incidentKey(),
                            plans.findVersion(incident.id(), versionNo).orElseThrow());
                });
    }

    // ---------- 只读查询 ----------

    /**
     * 查询指定方案版本（活动版本带出运行时执行状态）。只读，稳定排序。
     */
    @Transactional(readOnly = true)
    public PlanVersionView getVersion(String incidentKey, int versionNo) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        PlanVersion version = plans.findVersion(incident.id(), versionNo)
                .orElseThrow(() -> ApiException.notFound("方案版本不存在: " + versionNo));
        return toVersionView(incident.incidentKey(), version);
    }

    /**
     * 查询当前活动方案版本（含运行时执行状态）。只读。
     */
    @Transactional(readOnly = true)
    public PlanVersionView getActivePlan(String incidentKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        PlanVersion active = plans.findActive(incident.id())
                .orElseThrow(() -> ApiException.notFound("事件尚无已发布的方案版本: " + incidentKey));
        return toVersionView(incident.incidentKey(), active);
    }

    /**
     * 三方差异查询（只读、稳定排序）：按稳定 taskId 与边身份对齐三侧，
     * 返回自动采用的变更与显式冲突，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanDiffView diff(String incidentKey, int baseVersion, int leftVersion,
                             int rightVersion) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        MergeDiff diff = computeDiff(incident.id(), baseVersion, leftVersion, rightVersion);
        return toDiffView(baseVersion, leftVersion, rightVersion, diff);
    }

    /**
     * 合并证据查询（只读、稳定排序）：冻结的三方差异、全部冲突解决、
     * 最终任务集与边集。mergeKey 属于其他事件时按不存在处理（404）。
     */
    @Transactional(readOnly = true)
    public MergeEvidenceView getMergeEvidence(String incidentKey, String mergeKey) {
        Incident incident = incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        PlanMerge merge = merges.findByMergeKey(mergeKey)
                .filter(m -> m.incidentId() == incident.id())
                .orElseThrow(() -> ApiException.notFound("合并证据不存在: " + mergeKey));
        return new MergeEvidenceView(merge.mergeKey(), merge.requestId(), merge.baseVersionNo(),
                merge.leftVersionNo(), merge.rightVersionNo(), merge.resultVersionNo(),
                fromJson(merge.diffJson(), PlanDiffView.class),
                fromJsonList(merge.resolutionsJson(), MergeResolutionView.class),
                fromJsonList(merge.tasksJson(), PlanTaskView.class),
                fromJsonList(merge.edgesJson(), PlanEdgeView.class),
                merge.createdBy(), merge.createdAt());
    }

    // ---------- 三方合并与原子发布 ----------

    /**
     * 三方合并并原子发布：单事务内重新读取 base、两分支、当前活动版本与相关事件状态。
     * 任一分支 revision 与 expectedVersion 不符、活动版本已前进（409）或完整后态违规
     * （422）时整体失败且不生成合并版本；成功只创建一个新的 PUBLISHED 版本，
     * 冻结三方差异、全部冲突解决、最终任务集与边集，原分支标记 MERGED 保持不可变。
     * requestId 同参重放首次快照，异参 409，失败不占键；mergeKey 全局唯一。
     */
    @Transactional
    public MergeResultView merge(String incidentKey, String actor, MergeRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String mergeKey = requireText(req.mergeKey(), "mergeKey");
        if (req.baseVersion() == null || req.leftVersion() == null || req.rightVersion() == null) {
            throw ApiException.badRequest("baseVersion/leftVersion/rightVersion 不能为空");
        }
        if (req.leftExpectedVersion() == null || req.rightExpectedVersion() == null) {
            throw ApiException.badRequest("leftExpectedVersion/rightExpectedVersion 不能为空");
        }
        List<MergeResolutionInput> resolutionInputs = req.resolutions() == null ? List.of()
                : req.resolutions();
        String requestHash = hash(incidentKey, actor, mergeKey,
                String.valueOf(req.baseVersion()), String.valueOf(req.leftVersion()),
                String.valueOf(req.rightVersion()), String.valueOf(req.leftExpectedVersion()),
                String.valueOf(req.rightExpectedVersion()),
                canonicalResolutions(resolutionInputs));

        Incident incident = lockIncident(incidentKey);
        var existing = merges.findByRequestId(requestId);
        if (existing.isPresent()) {
            PlanMerge committed = existing.get();
            if (!committed.requestHash().equals(requestHash)) {
                throw ApiException.conflict("requestId 已被不同参数的请求使用: " + requestId);
            }
            return fromJson(committed.responseJson(), MergeResultView.class);
        }

        requireCommander(incident, actor);
        requireNotClosed(incident);
        if (req.leftVersion().equals(req.rightVersion())) {
            throw ApiException.badRequest("leftVersion 与 rightVersion 不能相同");
        }
        PlanVersion base = plans.findVersion(incident.id(), req.baseVersion())
                .orElseThrow(() -> ApiException.notFound("基版本不存在: " + req.baseVersion()));
        PlanVersion left = requireDraftBranch(incident.id(), req.leftVersion(), base);
        PlanVersion right = requireDraftBranch(incident.id(), req.rightVersion(), base);
        PlanVersion active = plans.findActive(incident.id()).orElse(null);
        if (active == null || active.id() != base.id()) {
            throw ApiException.conflict("活动方案版本已前进，baseVersion 不再是当前活动版本");
        }
        if (left.revision() != req.leftExpectedVersion()) {
            throw ApiException.conflict("左侧分支已变化: expectedVersion "
                    + req.leftExpectedVersion() + "，当前 " + left.revision());
        }
        if (right.revision() != req.rightExpectedVersion()) {
            throw ApiException.conflict("右侧分支已变化: expectedVersion "
                    + req.rightExpectedVersion() + "，当前 " + right.revision());
        }

        MergeDiff diff = computeDiff(incident.id(), req.baseVersion(), req.leftVersion(),
                req.rightVersion());
        List<Resolution> resolutions = new ArrayList<>();
        for (MergeResolutionInput input : resolutionInputs) {
            resolutions.add(toEngineResolution(input));
        }
        MergeOutcome outcome = PlanMergeEngine.apply(diff, resolutions);

        validateGraph(incident, outcome.tasks(), outcome.edges(), false);
        validateRuntimeGuards(incident, base, outcome);

        Instant now = now();
        int resultVersionNo = plans.maxVersionNo(incident.id()) + 1;
        long resultId = plans.insertVersion(new PlanVersion(0L, incident.id(), resultVersionNo,
                PlanVersionStatus.PUBLISHED, null, null, 0, mergeKey, actor, now, now));
        for (TaskContent task : outcome.tasks().values()) {
            plans.insertTask(resultId, new PlanTask(0L, resultId, task.taskId(), task.groupCode(),
                    task.title(), task.assignee(), now));
        }
        for (EdgeKey edge : outcome.edges()) {
            plans.insertEdge(resultId, new PlanEdge(0L, resultId, edge.fromTaskId(),
                    edge.toIncidentKey(), edge.toTaskId(), now));
        }
        plans.updateVersionStatus(base.id(), PlanVersionStatus.SUPERSEDED, null);
        plans.updateVersionStatus(left.id(), PlanVersionStatus.MERGED, null);
        plans.updateVersionStatus(right.id(), PlanVersionStatus.MERGED, null);
        syncRuntime(incident.id(), outcome.tasks(), now);

        MergeResultView result = new MergeResultView(mergeKey, requestId, req.baseVersion(),
                req.leftVersion(), req.rightVersion(), resultVersionNo,
                diff.conflicts().size(), now);
        PlanMerge evidence = new PlanMerge(0L, incident.id(), mergeKey, requestId, requestHash,
                req.baseVersion(), req.leftVersion(), req.rightVersion(), resultVersionNo,
                toJson(toDiffView(req.baseVersion(), req.leftVersion(), req.rightVersion(), diff)),
                toJson(toResolutionViews(resolutionInputs)),
                toJson(toTaskViews(outcome.tasks())),
                toJson(toEdgeViews(outcome.edges())), toJson(result), actor, now);
        try {
            merges.insert(evidence);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("mergeKey 已存在: " + mergeKey);
        }
        return result;
    }

    // ---------- 任务执行（活动版本运行时状态） ----------

    /**
     * 启动方案任务：仅当前指挥人；仅 PENDING 可启动；全部前置任务
     * （本事件内部边与跨事件边）COMPLETED 后才可启动，否则 409 并返回未满足前置列表。
     */
    @Transactional
    public PlanTaskView startTask(String incidentKey, String taskId, String actor,
                                  PlanTaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_task_start", hash(incidentKey, taskId, actor),
                PlanTaskView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion active = plans.findActive(incident.id())
                            .orElseThrow(() -> ApiException.conflict("事件尚无已发布的方案版本"));
                    PlanTask task = plans.findTask(active.id(), taskId)
                            .orElseThrow(() -> ApiException.notFound("活动版本中任务不存在: " + taskId));
                    PlanTaskState state = plans.findState(incident.id(), taskId)
                            .orElseThrow(() -> ApiException.conflict("任务运行时状态缺失: " + taskId));
                    if (state.status() != PlanTaskStatus.PENDING) {
                        throw ApiException.conflict(
                                "任务已处于 " + state.status() + "，不能启动");
                    }
                    List<String> unsatisfied = unsatisfiedPrerequisites(incident, active, taskId);
                    if (!unsatisfied.isEmpty()) {
                        throw ApiException.conflict("存在未完成的前置任务: "
                                + String.join(",", unsatisfied), List.copyOf(unsatisfied));
                    }
                    plans.markInProgress(incident.id(), taskId, actor, now());
                    return toTaskView(task, plans.findState(incident.id(), taskId).orElseThrow());
                });
    }

    /**
     * 完成方案任务：仅当前指挥人；仅 IN_PROGRESS 可完成；
     * COMPLETED 为终态，完成事实不可回退。
     */
    @Transactional
    public PlanTaskView completeTask(String incidentKey, String taskId, String actor,
                                     PlanTaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_task_complete", hash(incidentKey, taskId, actor),
                PlanTaskView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion active = plans.findActive(incident.id())
                            .orElseThrow(() -> ApiException.conflict("事件尚无已发布的方案版本"));
                    PlanTask task = plans.findTask(active.id(), taskId)
                            .orElseThrow(() -> ApiException.notFound("活动版本中任务不存在: " + taskId));
                    PlanTaskState state = plans.findState(incident.id(), taskId)
                            .orElseThrow(() -> ApiException.conflict("任务运行时状态缺失: " + taskId));
                    if (state.status() != PlanTaskStatus.IN_PROGRESS) {
                        throw ApiException.conflict(
                                "任务状态为 " + state.status() + "，不能完成");
                    }
                    plans.markCompleted(incident.id(), taskId, actor, now());
                    return toTaskView(task, plans.findState(incident.id(), taskId).orElseThrow());
                });
    }

    // ---------- 内部：三方差异 ----------

    private MergeDiff computeDiff(long incidentId, int baseVersion, int leftVersion,
                                  int rightVersion) {
        PlanVersion base = plans.findVersion(incidentId, baseVersion)
                .orElseThrow(() -> ApiException.notFound("基版本不存在: " + baseVersion));
        PlanVersion left = plans.findVersion(incidentId, leftVersion)
                .orElseThrow(() -> ApiException.notFound("左侧版本不存在: " + leftVersion));
        PlanVersion right = plans.findVersion(incidentId, rightVersion)
                .orElseThrow(() -> ApiException.notFound("右侧版本不存在: " + rightVersion));
        return PlanMergeEngine.diff(toTaskContents(plans.listTasks(base.id())),
                toTaskContents(plans.listTasks(left.id())),
                toTaskContents(plans.listTasks(right.id())),
                toEdgeKeys(plans.listEdges(base.id())),
                toEdgeKeys(plans.listEdges(left.id())),
                toEdgeKeys(plans.listEdges(right.id())));
    }

    private PlanVersion requireDraftBranch(long incidentId, int versionNo, PlanVersion base) {
        PlanVersion branch = plans.findVersion(incidentId, versionNo)
                .orElseThrow(() -> ApiException.notFound("分支版本不存在: " + versionNo));
        if (branch.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict("分支版本 " + versionNo + " 状态为 " + branch.status()
                    + "，不是可合并的 DRAFT");
        }
        if (branch.baseVersionId() == null || branch.baseVersionId() != base.id()) {
            throw ApiException.conflict("分支版本 " + versionNo + " 的基版本与 baseVersion 不一致");
        }
        return branch;
    }

    // ---------- 内部：完整图统一校验 ----------

    /**
     * 对完整任务集与依赖图统一校验：边不得引用不存在任务、不得成环；
     * 跨事件边目标事件须存在、非自身、非 CLOSED 且其活动版本含目标任务，
     * 其他事件活动边不得指向本事件被移除的任务；全局跨事件图不得成环。
     * initial 为 true 时（首版创建）违规抛 400，否则（合并后态）抛 422。
     */
    private void validateGraph(Incident incident, Map<String, TaskContent> tasks,
                               Set<EdgeKey> edges, boolean initial) {
        for (EdgeKey edge : edges) {
            if (!tasks.containsKey(edge.fromTaskId())) {
                throw graphViolation(initial, "边引用了不存在的前置任务: " + edge.id());
            }
            if (edge.toIncidentKey().isEmpty()) {
                if (!tasks.containsKey(edge.toTaskId())) {
                    throw graphViolation(initial, "边引用了不存在的目标任务: " + edge.id());
                }
                if (edge.fromTaskId().equals(edge.toTaskId())) {
                    throw graphViolation(initial, "不允许任务自依赖边: " + edge.id());
                }
            } else {
                validateCrossEdgeTarget(incident, edge, initial);
            }
        }
        assertAcyclic(incident.incidentKey(), tasks.keySet(), edges, initial);

        // 全局图锁：串行化跨事件环检测与发布，保证并发发布下最终图无环
        taskGraph.lockGraph();
        Map<String, Set<String>> graphTasks = new HashMap<>();
        for (GraphNode node : plans.listActiveGraphNodes()) {
            graphTasks.computeIfAbsent(node.incidentKey(), k -> new HashSet<>()).add(node.taskId());
        }
        List<GraphEdge> graphEdges = new ArrayList<>(plans.listActiveGraphEdges());
        // 以本事件新图替换其当前活动版本的图贡献
        graphTasks.put(incident.incidentKey(), new HashSet<>(tasks.keySet()));
        graphEdges.removeIf(e -> e.incidentKey().equals(incident.incidentKey()));
        for (EdgeKey edge : edges) {
            graphEdges.add(new GraphEdge(incident.incidentKey(), edge.fromTaskId(),
                    edge.toIncidentKey(), edge.toTaskId()));
        }
        // 其他事件活动边不得指向本事件被移除的任务
        for (GraphEdge edge : graphEdges) {
            if (!edge.incidentKey().equals(incident.incidentKey())
                    && edge.toIncidentKey().equals(incident.incidentKey())
                    && !tasks.containsKey(edge.toTaskId())) {
                throw graphViolation(initial, "事件 " + edge.incidentKey() + " 的依赖边指向本事件"
                        + "已移除的任务: " + edge.toTaskId());
            }
        }
        assertGlobalAcyclic(graphTasks, graphEdges, initial);
    }

    private void validateCrossEdgeTarget(Incident incident, EdgeKey edge, boolean initial) {
        if (edge.toIncidentKey().equals(incident.incidentKey())) {
            throw graphViolation(initial, "跨事件边目标不能是事件自身，内部边请留空 toIncidentKey: "
                    + edge.id());
        }
        Incident target = incidents.findByKey(edge.toIncidentKey())
                .orElseThrow(() -> graphViolation(initial,
                        "跨事件边目标事件不存在: " + edge.toIncidentKey()));
        if (target.status() == IncidentStatus.CLOSED) {
            throw graphViolation(initial, "跨事件边目标事件已 CLOSED: " + edge.toIncidentKey());
        }
        PlanVersion targetActive = plans.findActive(target.id())
                .orElseThrow(() -> graphViolation(initial,
                        "跨事件边目标事件尚无活动方案版本: " + edge.toIncidentKey()));
        if (plans.findTask(targetActive.id(), edge.toTaskId()).isEmpty()) {
            throw graphViolation(initial, "跨事件边目标任务不存在于目标事件活动版本: " + edge.id());
        }
    }

    /**
     * 本事件内部图环检测（DFS，三色标记）。
     */
    private void assertAcyclic(String incidentKey, Set<String> taskIds, Set<EdgeKey> edges,
                               boolean initial) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (EdgeKey edge : edges) {
            if (edge.toIncidentKey().isEmpty()) {
                adjacency.computeIfAbsent(edge.fromTaskId(), k -> new ArrayList<>())
                        .add(edge.toTaskId());
            }
        }
        Map<String, Integer> color = new HashMap<>();
        for (String taskId : taskIds) {
            if (hasCycle(taskId, adjacency, color)) {
                throw graphViolation(initial, "依赖图存在环（事件 " + incidentKey + " 内）: " + taskId);
            }
        }
    }

    private static boolean hasCycle(String node, Map<String, List<String>> adjacency,
                                    Map<String, Integer> color) {
        Integer state = color.get(node);
        if (state != null) {
            return state == 1;
        }
        color.put(node, 1);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            if (hasCycle(next, adjacency, color)) {
                return true;
            }
        }
        color.put(node, 2);
        return false;
    }

    /**
     * 全局跨事件图环检测：节点为（事件键, 稳定 taskId），边含各事件内部边与跨事件边。
     */
    private void assertGlobalAcyclic(Map<String, Set<String>> graphTasks,
                                     List<GraphEdge> graphEdges, boolean initial) {
        record Node(String incidentKey, String taskId) {
        }
        Map<Node, List<Node>> adjacency = new HashMap<>();
        for (GraphEdge edge : graphEdges) {
            Node from = new Node(edge.incidentKey(), edge.fromTaskId());
            String toIncident = edge.toIncidentKey().isEmpty() ? edge.incidentKey()
                    : edge.toIncidentKey();
            Node to = new Node(toIncident, edge.toTaskId());
            adjacency.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
        }
        Map<Node, Integer> color = new HashMap<>();
        Deque<Node> stack = new ArrayDeque<>();
        for (Map.Entry<String, Set<String>> entry : graphTasks.entrySet()) {
            for (String taskId : entry.getValue()) {
                Node start = new Node(entry.getKey(), taskId);
                if (color.containsKey(start)) {
                    continue;
                }
                // 迭代 DFS，三色标记
                stack.push(start);
                Map<Node, Integer> edgeIndex = new HashMap<>();
                while (!stack.isEmpty()) {
                    Node node = stack.peek();
                    color.putIfAbsent(node, 1);
                    List<Node> nexts = adjacency.getOrDefault(node, List.of());
                    int index = edgeIndex.getOrDefault(node, 0);
                    if (index < nexts.size()) {
                        edgeIndex.put(node, index + 1);
                        Node next = nexts.get(index);
                        Integer nextColor = color.get(next);
                        if (nextColor != null && nextColor == 1) {
                            throw graphViolation(initial, "跨事件依赖图存在环: "
                                    + next.incidentKey() + ":" + next.taskId());
                        }
                        if (nextColor == null) {
                            stack.push(next);
                        }
                    } else {
                        color.put(node, 2);
                        stack.pop();
                    }
                }
            }
        }
    }

    /**
     * 合并后态的运行时保护：已 COMPLETED 任务不得移出任务集（完成事实不可回退）；
     * 正在执行任务不得移出、不得更换负责人、不得新增未满足前置依赖。
     */
    private void validateRuntimeGuards(Incident incident, PlanVersion base, MergeOutcome outcome) {
        Set<EdgeKey> baseEdges = toEdgeKeys(plans.listEdges(base.id()));
        Map<String, PlanTaskState> states = new LinkedHashMap<>();
        for (PlanTaskState state : plans.listStates(incident.id())) {
            states.put(state.taskId(), state);
        }
        for (PlanTaskState state : states.values()) {
            TaskContent merged = outcome.tasks().get(state.taskId());
            if (state.status() == PlanTaskStatus.COMPLETED && merged == null) {
                throw ApiException.unprocessable("已 COMPLETED 任务不可移除，完成事实不可回退: "
                        + state.taskId(), null);
            }
            if (state.status() == PlanTaskStatus.IN_PROGRESS) {
                if (merged == null) {
                    throw ApiException.unprocessable("正在执行的任务不可移除: " + state.taskId(), null);
                }
                if (!merged.assignee().equals(state.assignee())) {
                    throw ApiException.unprocessable("正在执行的任务不得更换负责人: " + state.taskId(),
                            null);
                }
                List<String> newUnsatisfied = new ArrayList<>();
                for (EdgeKey edge : outcome.edges()) {
                    if (baseEdges.contains(edge)) {
                        continue;
                    }
                    boolean incoming = edge.toIncidentKey().isEmpty()
                            && edge.toTaskId().equals(state.taskId());
                    if (!incoming) {
                        continue;
                    }
                    PlanTaskState from = states.get(edge.fromTaskId());
                    if (from == null || from.status() != PlanTaskStatus.COMPLETED) {
                        newUnsatisfied.add(edge.id());
                    }
                }
                if (!newUnsatisfied.isEmpty()) {
                    throw ApiException.unprocessable("正在执行的任务不得新增未满足前置依赖: "
                            + state.taskId(), List.copyOf(newUnsatisfied));
                }
            }
        }
    }

    // ---------- 内部：任务执行辅助 ----------

    /**
     * 未满足前置列表：指向 taskId 的边，其前置任务运行时状态非 COMPLETED。
     * 覆盖本事件内部边与其他事件活动版本指向本任务的跨事件边。
     */
    private List<String> unsatisfiedPrerequisites(Incident incident, PlanVersion active,
                                                  String taskId) {
        Map<String, PlanTaskState> states = new LinkedHashMap<>();
        for (PlanTaskState state : plans.listStates(incident.id())) {
            states.put(state.taskId(), state);
        }
        List<String> unsatisfied = new ArrayList<>();
        for (PlanEdge edge : plans.listEdges(active.id())) {
            if (edge.toIncidentKey().isEmpty() && edge.toTaskId().equals(taskId)) {
                PlanTaskState from = states.get(edge.fromTaskId());
                if (from == null || from.status() != PlanTaskStatus.COMPLETED) {
                    unsatisfied.add(edge.fromTaskId());
                }
            }
        }
        // 其他事件活动版本指向本任务的跨事件边：源任务须 COMPLETED
        for (GraphEdge edge : plans.listActiveGraphEdges()) {
            if (!edge.incidentKey().equals(incident.incidentKey())
                    && edge.toIncidentKey().equals(incident.incidentKey())
                    && edge.toTaskId().equals(taskId)) {
                Incident source = incidents.findByKey(edge.incidentKey()).orElse(null);
                PlanTaskState from = source == null ? null
                        : plans.findState(source.id(), edge.fromTaskId()).orElse(null);
                if (from == null || from.status() != PlanTaskStatus.COMPLETED) {
                    unsatisfied.add(edge.incidentKey() + ":" + edge.fromTaskId());
                }
            }
        }
        return unsatisfied.stream().sorted().toList();
    }

    /**
     * 发布时同步运行时状态：新任务插入 PENDING；保留任务的 PENDING 行同步计划负责人；
     * 被移除任务的行删除（COMPLETED/IN_PROGRESS 由合并校验保护）。
     */
    private void syncRuntime(long incidentId, Map<String, TaskContent> tasks, Instant now) {
        Map<String, PlanTaskState> states = new LinkedHashMap<>();
        for (PlanTaskState state : plans.listStates(incidentId)) {
            states.put(state.taskId(), state);
        }
        for (TaskContent task : tasks.values()) {
            PlanTaskState state = states.get(task.taskId());
            if (state == null) {
                plans.insertState(new PlanTaskState(0L, incidentId, task.taskId(),
                        PlanTaskStatus.PENDING, task.assignee(), null, null, null, null,
                        now, now));
            } else if (state.status() == PlanTaskStatus.PENDING
                    && !state.assignee().equals(task.assignee())) {
                plans.updateStateAssignee(incidentId, task.taskId(), task.assignee(), now);
            }
        }
        for (PlanTaskState state : states.values()) {
            if (!tasks.containsKey(state.taskId())) {
                plans.deleteState(incidentId, state.taskId());
            }
        }
    }

    // ---------- 内部：视图组装 ----------

    private PlanVersionView toVersionView(String incidentKey, PlanVersion version) {
        Map<String, PlanTaskState> states = new LinkedHashMap<>();
        if (version.status() == PlanVersionStatus.PUBLISHED) {
            PlanVersion active = plans.findActive(version.incidentId()).orElse(null);
            if (active != null && active.id() == version.id()) {
                for (PlanTaskState state : plans.listStates(version.incidentId())) {
                    states.put(state.taskId(), state);
                }
            }
        }
        List<PlanTaskView> tasks = plans.listTasks(version.id()).stream()
                .map(t -> toTaskView(t, states.get(t.taskId())))
                .sorted(Comparator.comparing(PlanTaskView::taskId))
                .toList();
        List<PlanEdgeView> edges = plans.listEdges(version.id()).stream()
                .map(e -> new PlanEdgeView(e.fromTaskId(), e.toTaskId(), e.toIncidentKey()))
                .toList();
        Integer baseVersionNo = version.baseVersionId() == null ? null
                : plans.findVersionById(version.baseVersionId())
                .map(PlanVersion::versionNo).orElse(null);
        return new PlanVersionView(incidentKey, version.versionNo(), version.status().name(),
                baseVersionNo, version.branch(), version.revision(),
                tasks, edges, version.createdBy(), version.createdAt(), version.publishedAt());
    }

    private static PlanTaskView toTaskView(PlanTask task, PlanTaskState state) {
        return new PlanTaskView(task.taskId(), task.groupCode(), task.title(), task.assignee(),
                state == null ? null : state.status().name(),
                state == null ? null : state.startedBy(),
                state == null ? null : state.startedAt(),
                state == null ? null : state.completedBy(),
                state == null ? null : state.completedAt());
    }

    private PlanDiffView toDiffView(int baseVersion, int leftVersion, int rightVersion,
                                    MergeDiff diff) {
        List<DiffChangeView> changes = diff.changes().stream()
                .map(PlanService::toChangeView)
                .toList();
        List<DiffConflictView> conflicts = diff.conflicts().stream()
                .map(PlanService::toConflictView)
                .toList();
        return new PlanDiffView(baseVersion, leftVersion, rightVersion, changes, conflicts);
    }

    private static DiffChangeView toChangeView(Change change) {
        PlanEdgeView edge = change.edge() == null ? null
                : new PlanEdgeView(change.edge().fromTaskId(), change.edge().toTaskId(),
                change.edge().toIncidentKey());
        return new DiffChangeView(change.kind().name(), change.source().name(), change.taskId(),
                edge, toContentView(change.content()));
    }

    private static DiffConflictView toConflictView(Conflict conflict) {
        PlanEdgeView edge = conflict.edge() == null ? null
                : new PlanEdgeView(conflict.edge().fromTaskId(), conflict.edge().toTaskId(),
                conflict.edge().toIncidentKey());
        PlanEdgeView reverseEdge = conflict.reverseEdge() == null ? null
                : new PlanEdgeView(conflict.reverseEdge().fromTaskId(),
                conflict.reverseEdge().toTaskId(), conflict.reverseEdge().toIncidentKey());
        return new DiffConflictView(conflict.conflictId(), conflict.type().name(),
                conflict.taskId(), edge, reverseEdge,
                toContentView(conflict.baseTask()), toContentView(conflict.leftTask()),
                toContentView(conflict.rightTask()), conflict.leftPresent(),
                conflict.rightPresent());
    }

    private static PlanTaskView toContentView(TaskContent content) {
        if (content == null) {
            return null;
        }
        return new PlanTaskView(content.taskId(), content.groupCode(), content.title(),
                content.assignee(), null, null, null, null, null);
    }

    private List<MergeResolutionView> toResolutionViews(List<MergeResolutionInput> inputs) {
        return inputs.stream()
                .map(i -> new MergeResolutionView(i.conflictId(), i.choice(),
                        i.manualTask() == null ? null
                                : new PlanTaskView(i.manualTask().taskId(),
                                i.manualTask().groupCode(), i.manualTask().title(),
                                i.manualTask().assignee(), null, null, null, null, null),
                        i.manualEdgePresent()))
                .toList();
    }

    private List<PlanTaskView> toTaskViews(Map<String, TaskContent> tasks) {
        return tasks.values().stream().map(PlanService::toContentView).toList();
    }

    private List<PlanEdgeView> toEdgeViews(Set<EdgeKey> edges) {
        return edges.stream()
                .map(e -> new PlanEdgeView(e.fromTaskId(), e.toTaskId(), e.toIncidentKey()))
                .toList();
    }

    // ---------- 内部：参数与状态辅助 ----------

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private PlanVersion requireEditableDraft(Incident incident, String actor, int versionNo) {
        requireCommander(incident, actor);
        requireNotClosed(incident);
        PlanVersion version = plans.findVersion(incident.id(), versionNo)
                .orElseThrow(() -> ApiException.notFound("方案版本不存在: " + versionNo));
        if (version.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict("版本 " + versionNo + " 状态为 " + version.status()
                    + "，不可编辑（已发布/已合并版本保持不可变）");
        }
        return version;
    }

    private void validateEdgeEndpoints(Incident incident, long draftId, String fromTaskId,
                                       String toIncidentKey, String toTaskId) {
        if (plans.findTask(draftId, fromTaskId).isEmpty()) {
            throw ApiException.notFound("草稿中前置任务不存在: " + fromTaskId);
        }
        if (toIncidentKey.isEmpty()) {
            if (plans.findTask(draftId, toTaskId).isEmpty()) {
                throw ApiException.notFound("草稿中目标任务不存在: " + toTaskId);
            }
            if (fromTaskId.equals(toTaskId)) {
                throw ApiException.badRequest("不允许任务自依赖边: " + fromTaskId);
            }
        } else {
            if (toIncidentKey.equals(incident.incidentKey())) {
                throw ApiException.badRequest("跨事件边目标不能是事件自身，内部边请留空 toIncidentKey");
            }
            Incident target = incidents.findByKey(toIncidentKey)
                    .orElseThrow(() -> ApiException.notFound("跨事件边目标事件不存在: "
                            + toIncidentKey));
            if (target.status() == IncidentStatus.CLOSED) {
                throw ApiException.illegalTransition("跨事件边目标事件已 CLOSED: " + toIncidentKey);
            }
            PlanVersion targetActive = plans.findActive(target.id())
                    .orElseThrow(() -> ApiException.conflict(
                            "跨事件边目标事件尚无活动方案版本: " + toIncidentKey));
            if (plans.findTask(targetActive.id(), toTaskId).isEmpty()) {
                throw ApiException.conflict("跨事件边目标任务不存在于目标事件活动版本: "
                        + toIncidentKey + ":" + toTaskId);
            }
        }
    }

    private Map<String, TaskContent> parseTasks(List<PlanTaskInput> inputs) {
        Map<String, TaskContent> tasks = new LinkedHashMap<>();
        for (PlanTaskInput input : inputs) {
            String taskId = requireText(input.taskId(), "taskId");
            String groupCode = requireText(input.groupCode(), "groupCode");
            String title = requireText(input.title(), "title");
            String assignee = requireText(input.assignee(), "assignee");
            if (tasks.putIfAbsent(taskId, new TaskContent(taskId, groupCode, title,
                    assignee)) != null) {
                throw ApiException.badRequest("重复 taskId: " + taskId);
            }
        }
        return tasks;
    }

    private Set<EdgeKey> parseEdges(List<PlanEdgeInput> inputs) {
        Set<EdgeKey> edges = new TreeSet<>();
        for (PlanEdgeInput input : inputs) {
            String fromTaskId = requireText(input.fromTaskId(), "fromTaskId");
            String toTaskId = requireText(input.toTaskId(), "toTaskId");
            EdgeKey edge = new EdgeKey(fromTaskId, normalizeToIncidentKey(input.toIncidentKey()),
                    toTaskId);
            if (!edges.add(edge)) {
                throw ApiException.badRequest("重复依赖边: " + edge.id());
            }
        }
        return edges;
    }

    private Resolution toEngineResolution(MergeResolutionInput input) {
        String conflictId = requireText(input.conflictId(), "conflictId");
        String choiceText = requireText(input.choice(), "choice");
        Choice choice;
        try {
            choice = Choice.valueOf(choiceText);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知解决选择: " + choiceText);
        }
        TaskContent manualTask = input.manualTask() == null ? null
                : new TaskContent(requireText(input.manualTask().taskId(), "manualTask.taskId"),
                requireText(input.manualTask().groupCode(), "manualTask.groupCode"),
                requireText(input.manualTask().title(), "manualTask.title"),
                requireText(input.manualTask().assignee(), "manualTask.assignee"));
        return new Resolution(conflictId, choice, manualTask, input.manualEdgePresent());
    }

    private String canonicalResolutions(List<MergeResolutionInput> inputs) {
        // 冲突解决按 conflictId 排序后参与哈希：换序等价
        return inputs.stream()
                .sorted(Comparator.comparing(i -> i.conflictId() == null ? "" : i.conflictId()))
                .map(i -> String.join("|",
                        String.valueOf(i.conflictId()), String.valueOf(i.choice()),
                        i.manualTask() == null ? "" : String.join(",",
                                String.valueOf(i.manualTask().taskId()),
                                String.valueOf(i.manualTask().groupCode()),
                                String.valueOf(i.manualTask().title()),
                                String.valueOf(i.manualTask().assignee())),
                        String.valueOf(i.manualEdgePresent())))
                .toList()
                .toString();
    }

    private static String normalizeToIncidentKey(String toIncidentKey) {
        return toIncidentKey == null ? "" : toIncidentKey.strip();
    }

    private static List<TaskContent> toTaskContents(List<PlanTask> tasks) {
        return tasks.stream()
                .map(t -> new TaskContent(t.taskId(), t.groupCode(), t.title(), t.assignee()))
                .toList();
    }

    private static Set<EdgeKey> toEdgeKeys(List<PlanEdge> edges) {
        Set<EdgeKey> keys = new TreeSet<>();
        for (PlanEdge edge : edges) {
            keys.add(new EdgeKey(edge.fromTaskId(), edge.toIncidentKey(), edge.toTaskId()));
        }
        return keys;
    }

    private ApiException graphViolation(boolean initial, String message) {
        return initial ? ApiException.badRequest(message)
                : ApiException.unprocessable(message, null);
    }

    private static void requireCommander(Incident incident, String actor) {
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander()) + " 能执行该操作");
        }
    }

    private static void requireNotClosed(Incident incident) {
        if (incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition("事件已关闭，不能再变更处置方案");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    // ---------- 内部：commandKey 幂等（与 IncidentService 同一约定） ----------

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化。
     */
    private <T> T runIdempotent(String commandKey, String operation, String requestHash,
                                Class<T> type, Supplier<T> business) {
        var existing = commandKeys.find(commandKey);
        if (existing.isPresent()) {
            return replay(existing.get(), operation, requestHash, type);
        }
        try {
            commandKeys.insertPlaceholder(commandKey, operation, requestHash, now());
        } catch (DuplicateKeyException e) {
            var committed = commandKeys.findForUpdate(commandKey)
                    .orElseThrow(() -> ApiException.conflict("commandKey 处理冲突: " + commandKey));
            return replay(committed, operation, requestHash, type);
        }
        T result = business.get();
        commandKeys.fillResponse(commandKey, 200, toJson(result));
        return result;
    }

    private <T> T replay(CommandKeyRecord record, String operation, String requestHash,
                         Class<T> type) {
        if (!record.operation().equals(operation) || !record.requestHash().equals(requestHash)) {
            throw ApiException.conflict("commandKey 已被不同参数的请求使用: " + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("commandKey 正在处理中: " + record.commandKey());
        }
        return fromJson(record.responseBody(), type);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private <T> List<T> fromJsonList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            elementType));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("快照反序列化失败", e);
        }
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
