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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.CommandKeyRecord;
import com.example.starter.incident.CommandKeyRepository;
import com.example.starter.incident.Incident;
import com.example.starter.incident.IncidentRepository;
import com.example.starter.incident.IncidentStatus;
import com.example.starter.incident.IncidentTaskRepository;
import com.example.starter.incident.plan.PlanMergeEngine.Choice;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeConflict;
import com.example.starter.incident.plan.PlanMergeEngine.EdgeKey;
import com.example.starter.incident.plan.PlanMergeEngine.MergeConflict;
import com.example.starter.incident.plan.PlanMergeEngine.MergeOutcome;
import com.example.starter.incident.plan.PlanMergeEngine.Resolution;
import com.example.starter.incident.plan.PlanMergeEngine.ResolvedPlan;
import com.example.starter.incident.plan.PlanMergeEngine.TaskConflict;
import com.example.starter.incident.plan.PlanMergeEngine.TaskFields;
import com.example.starter.incident.plan.PlanVersionRepository.GlobalEdge;
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
import com.example.starter.incident.plan.dto.PlanResponses.ConflictView;
import com.example.starter.incident.plan.dto.PlanResponses.EdgeDiffEntry;
import com.example.starter.incident.plan.dto.PlanResponses.MergeEvidenceView;
import com.example.starter.incident.plan.dto.PlanResponses.MergeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanDiffView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanEdgeView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanTaskView;
import com.example.starter.incident.plan.dto.PlanResponses.PlanVersionView;
import com.example.starter.incident.plan.dto.PlanResponses.TaskDiffEntry;
import com.example.starter.incident.plan.dto.PlanResponses.TaskFieldsView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置方案分支与三方合并服务。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定事件行；合并发布额外持有
 * task_graph_lock 全局锁做跨事件环检测，与任务执行、依赖变更及另一合并
 * 按事务提交顺序串行，不发布混合版本。
 * 幂等约定：requestId/commandKey 全局唯一，同键同参重放首次快照（合并请求
 * 的冲突解决项按 conflictId 排序后参与摘要，换序等价），异参 409，失败不占键；
 * mergeKey 全局唯一，同键同内容返回首次证据，同键不同内容 409。
 */
@Service
public class PlanService {

    private static final String SEP = "\\u001F";

    /** 依赖图节点分隔符（节点 = 事件键 + 分隔符 + 任务 id）。 */
    private static final String NODE_SEP = "";

    /** 同一基准版本允许的 DRAFT 分支上限。 */
    private static final int MAX_BRANCHES_PER_BASE = 2;

    private final IncidentRepository incidents;
    private final IncidentTaskRepository taskGraph;
    private final PlanVersionRepository versions;
    private final PlanMergeRepository merges;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlanService(IncidentRepository incidents, IncidentTaskRepository taskGraph,
                       PlanVersionRepository versions, PlanMergeRepository merges,
                       CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.incidents = incidents;
        this.taskGraph = taskGraph;
        this.versions = versions;
        this.merges = merges;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建并发布初始方案版本：仅当前指挥人；事件未 CLOSED；尚无任何已发布版本。
     * 边端点必须存在（内部任务在任务集内，跨事件任务在目标事件当前活动版本内），
     * 在依赖图全局锁内做环检测，成环则 409 且不留部分数据。
     */
    @Transactional
    public PlanVersionView createInitialVersion(String incidentKey, String actor,
                                                PlanVersionCreateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        List<PlanTaskInput> taskInputs = req.tasks() == null ? List.of() : req.tasks();
        List<PlanEdgeInput> edgeInputs = req.edges() == null ? List.of() : req.edges();
        Map<String, TaskFields> taskFields = parseTaskInputs(taskInputs);
        Set<EdgeKey> edgeKeys = parseEdgeInputs(incidentKey, edgeInputs);
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(requestId, "plan_create",
                hash(incidentKey, actor, canonicalTasks(taskFields), canonicalEdges(edgeKeys)),
                PlanVersionView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    if (versions.findActiveVersion(incidentKey).isPresent()) {
                        throw ApiException.conflict("已存在已发布的方案版本，不能重复创建初始版本");
                    }
                    validateEdgeEndpoints(incidentKey, taskFields.keySet(), edgeKeys);
                    taskGraph.lockGraph();
                    if (hasCycle(incidentKey, edgeKeys)) {
                        throw ApiException.conflict("依赖边会形成环，初始版本被拒绝");
                    }
                    Instant now = now();
                    long versionId = versions.insertVersion(new PlanVersion(0L, incidentKey,
                            versions.maxVersionNo(incidentKey) + 1, PlanVersionStatus.PUBLISHED,
                            null, null, 0, null, null, actor, now, now));
                    insertTasks(versionId, taskFields, now);
                    insertEdges(versionId, edgeKeys, now);
                    return toVersionView(versions.findVersion(versionId).orElseThrow());
                });
    }

    /**
     * 从当前活动 PUBLISHED 版本创建 DRAFT 分支：复制任务与边，
     * 按创建顺序分配 LEFT/RIGHT 侧，同一基准至多两个分支。
     */
    @Transactional
    public PlanVersionView createBranch(String incidentKey, String actor, long baseVersionId,
                                        BranchCreateRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(requestId, "plan_branch", hash(incidentKey, actor, baseVersionId),
                PlanVersionView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion base = versions.findVersion(baseVersionId)
                            .filter(v -> v.incidentKey().equals(incidentKey))
                            .orElseThrow(() -> ApiException.notFound(
                                    "基准版本不存在: " + baseVersionId));
                    if (base.status() != PlanVersionStatus.PUBLISHED) {
                        throw ApiException.conflict("只能从 PUBLISHED 版本创建分支，当前状态: "
                                + base.status());
                    }
                    PlanVersion active = versions.findActiveVersion(incidentKey).orElseThrow();
                    if (active.id() != base.id()) {
                        throw ApiException.conflict("基准版本已不是当前活动版本，不能创建分支");
                    }
                    List<PlanVersion> drafts = versions.listDrafts(base.id());
                    if (drafts.size() >= MAX_BRANCHES_PER_BASE) {
                        throw ApiException.conflict("同一基准版本至多 "
                                + MAX_BRANCHES_PER_BASE + " 个修订分支");
                    }
                    String side = drafts.stream().anyMatch(d -> "LEFT".equals(d.branchSide()))
                            ? "RIGHT" : "LEFT";
                    Instant now = now();
                    long draftId = versions.insertVersion(new PlanVersion(0L, incidentKey,
                            versions.maxVersionNo(incidentKey) + 1, PlanVersionStatus.DRAFT,
                            base.id(), side, 1, null, null, actor, now, null));
                    for (PlanTask task : versions.listTasks(base.id())) {
                        versions.insertTask(new PlanTask(0L, draftId, task.taskId(), task.title(),
                                task.assignee(), now, now));
                    }
                    for (PlanEdge edge : versions.listEdges(base.id())) {
                        versions.insertEdge(new PlanEdge(0L, draftId, edge.fromTaskId(),
                                edge.toIncidentKey(), edge.toTaskId(), now));
                    }
                    return toVersionView(versions.findVersion(draftId).orElseThrow());
                });
    }

    /**
     * 写入草稿任务（存在即改、不存在即增）：仅 DRAFT 可编辑，每次修改修订计数 +1。
     */
    @Transactional
    public PlanVersionView upsertDraftTask(String incidentKey, String actor, long versionId,
                                           String taskId, DraftTaskRequest req) {
        String stableTaskId = requireText(taskId, "taskId");
        String title = requireText(req.title(), "title");
        String assignee = req.assignee() == null || req.assignee().isBlank()
                ? null : req.assignee().strip();
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        requireNotClosed(incident);
        PlanVersion draft = requireDraft(incidentKey, versionId);
        Instant now = now();
        if (versions.findTask(draft.id(), stableTaskId).isPresent()) {
            versions.updateTaskFields(draft.id(), stableTaskId, title, assignee, now);
        } else {
            versions.insertTask(new PlanTask(0L, draft.id(), stableTaskId, title, assignee,
                    now, now));
        }
        versions.bumpRevision(draft.id());
        return toVersionView(versions.findVersion(draft.id()).orElseThrow());
    }

    /**
     * 删除草稿任务并级联删除其相关边，修订计数 +1。
     */
    @Transactional
    public PlanVersionView deleteDraftTask(String incidentKey, String actor, long versionId,
                                           String taskId) {
        String stableTaskId = requireText(taskId, "taskId");
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        requireNotClosed(incident);
        PlanVersion draft = requireDraft(incidentKey, versionId);
        if (versions.findTask(draft.id(), stableTaskId).isEmpty()) {
            throw ApiException.notFound("草稿任务不存在: " + stableTaskId);
        }
        versions.deleteEdgesOfTask(draft.id(), incidentKey, stableTaskId);
        versions.deleteTask(draft.id(), stableTaskId);
        versions.bumpRevision(draft.id());
        return toVersionView(versions.findVersion(draft.id()).orElseThrow());
    }

    /**
     * 新增草稿依赖边：端点必须存在，拒绝自环、重复边与草稿内成环，修订计数 +1。
     */
    @Transactional
    public PlanVersionView addDraftEdge(String incidentKey, String actor, long versionId,
                                        DraftEdgeRequest req) {
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        requireNotClosed(incident);
        PlanVersion draft = requireDraft(incidentKey, versionId);
        EdgeKey edge = parseEdge(incidentKey, req.fromTaskId(), req.toIncidentKey(),
                req.toTaskId());
        validateEdgeEndpoints(incidentKey, draftTaskIds(draft.id()), Set.of(edge));
        Set<EdgeKey> current = new HashSet<>(edgeKeys(versions.listEdges(draft.id())));
        if (current.contains(edge)) {
            throw ApiException.conflict("依赖边已存在: " + edge.canonical());
        }
        current.add(edge);
        if (hasLocalCycle(current)) {
            throw ApiException.conflict("依赖边会在草稿内形成环: " + edge.canonical());
        }
        versions.insertEdge(new PlanEdge(0L, draft.id(), edge.fromTaskId(), edge.toIncidentKey(),
                edge.toTaskId(), now()));
        versions.bumpRevision(draft.id());
        return toVersionView(versions.findVersion(draft.id()).orElseThrow());
    }

    /**
     * 删除草稿依赖边，修订计数 +1。
     */
    @Transactional
    public PlanVersionView deleteDraftEdge(String incidentKey, String actor, long versionId,
                                           DraftEdgeRequest req) {
        Incident incident = lockIncident(incidentKey);
        requireCommander(incident, actor);
        requireNotClosed(incident);
        PlanVersion draft = requireDraft(incidentKey, versionId);
        EdgeKey edge = parseEdge(incidentKey, req.fromTaskId(), req.toIncidentKey(),
                req.toTaskId());
        if (versions.deleteEdge(draft.id(), edge.fromTaskId(), edge.toIncidentKey(),
                edge.toTaskId()) == 0) {
            throw ApiException.notFound("依赖边不存在: " + edge.canonical());
        }
        versions.bumpRevision(draft.id());
        return toVersionView(versions.findVersion(draft.id()).orElseThrow());
    }

    /**
     * 三方差异查询（只读）：按稳定 taskId 与边键比较 base/left/right，
     * 返回自动采用项与全部显式冲突，稳定排序，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanDiffView diff(String incidentKey, long baseVersionId, long leftVersionId,
                             long rightVersionId) {
        requireIncident(incidentKey);
        MergeOutcome outcome = computeOutcome(incidentKey, baseVersionId, leftVersionId,
                rightVersionId);
        return toDiffView(baseVersionId, leftVersionId, rightVersionId, outcome);
    }

    /**
     * 提交合并（原子发布）：在一个事务内重新读取基准、两分支、当前活动版本
     * 及相关事件状态；任一分支变化、活动版本已前进或完整后态违规，整次 409/422
     * 且不生成合并版本。成功只创建一个新的 PUBLISHED 版本，冻结三方差异、
     * 全部冲突解决、最终任务集与边集；原分支标记 MERGED 保持不可变。
     */
    @Transactional
    public MergeView merge(String incidentKey, String actor, MergeRequest req) {
        String requestId = requireText(req.requestId(), "requestId");
        String mergeKey = requireText(req.mergeKey(), "mergeKey");
        long baseVersionId = requireId(req.baseVersionId(), "baseVersionId");
        long leftVersionId = requireId(req.leftVersionId(), "leftVersionId");
        long rightVersionId = requireId(req.rightVersionId(), "rightVersionId");
        long leftExpected = requireId(req.leftExpectedVersion(), "leftExpectedVersion");
        long rightExpected = requireId(req.rightExpectedVersion(), "rightExpectedVersion");
        if (leftVersionId == rightVersionId) {
            throw ApiException.badRequest("左右分支不能是同一版本");
        }
        List<MergeResolutionItem> items = req.resolutions() == null ? List.of()
                : req.resolutions();
        List<MergeResolutionItem> canonical = canonicalResolutions(incidentKey, items);
        Incident incident = lockIncident(incidentKey);
        String requestHash = hash(incidentKey, actor, mergeKey, baseVersionId, leftVersionId,
                rightVersionId, leftExpected, rightExpected, toJson(canonical));
        return runIdempotent(requestId, "plan_merge", requestHash, MergeView.class, () -> {
            requireCommander(incident, actor);
            requireNotClosed(incident);
            var existing = merges.findByMergeKey(mergeKey);
            if (existing.isPresent()) {
                PlanMergeRecord record = existing.get();
                if (!record.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("mergeKey 已被不同内容的合并使用: " + mergeKey);
                }
                return toMergeView(record);
            }

            PlanVersion base = requireVersion(incidentKey, baseVersionId);
            PlanVersion left = requireVersion(incidentKey, leftVersionId);
            PlanVersion right = requireVersion(incidentKey, rightVersionId);
            if (base.status() != PlanVersionStatus.PUBLISHED) {
                throw ApiException.conflict("基准版本不是 PUBLISHED 版本: " + baseVersionId);
            }
            PlanVersion active = versions.findActiveVersion(incidentKey).orElseThrow();
            if (active.id() != base.id()) {
                throw ApiException.conflict("活动方案版本已前进，基准版本不再是当前活动版本");
            }
            requireBranch(base, left, leftExpected, "左");
            requireBranch(base, right, rightExpected, "右");

            MergeOutcome outcome = computeOutcome(incidentKey, baseVersionId, leftVersionId,
                    rightVersionId);
            List<Resolution> resolutions = parseResolutions(incidentKey, canonical);
            ResolvedPlan plan = PlanMergeEngine.apply(outcome, resolutions);
            validateMergedState(incidentKey, base.id(), plan);

            Instant now = now();
            long resultId = versions.insertVersion(new PlanVersion(0L, incidentKey,
                    versions.maxVersionNo(incidentKey) + 1, PlanVersionStatus.PUBLISHED,
                    base.id(), null, 0, left.id(), right.id(), actor, now, now));
            insertTasks(resultId, plan.tasks(), now);
            insertEdges(resultId, plan.edges(), now);
            versions.markMerged(left.id());
            versions.markMerged(right.id());

            PlanDiffView diffView = toDiffView(baseVersionId, leftVersionId, rightVersionId,
                    outcome);
            List<PlanTaskView> finalTasks = toVersionView(
                    versions.findVersion(resultId).orElseThrow()).tasks();
            List<PlanEdgeView> finalEdges = plan.edges().stream()
                    .map(e -> new PlanEdgeView(e.fromTaskId(), e.toIncidentKey(), e.toTaskId()))
                    .toList();
            PlanMergeRecord record = new PlanMergeRecord(0L, mergeKey, requestId, incidentKey,
                    base.id(), left.id(), right.id(), resultId, requestHash,
                    toJson(diffView), toJson(canonical), toJson(finalTasks), toJson(finalEdges),
                    actor, now);
            merges.insert(record);
            return toMergeView(merges.findByMergeKey(mergeKey).orElseThrow());
        });
    }

    /**
     * 开始执行活动版本任务：仅当前指挥人；任务须已指派规划负责人；
     * 全部前置任务 COMPLETED 后才可开始，否则 409 并返回未满足前置列表。
     */
    @Transactional
    public PlanTaskView startTask(String incidentKey, String actor, String taskId,
                                  TaskStartRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String stableTaskId = requireText(taskId, "taskId");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_task_start", hash(incidentKey, actor, stableTaskId),
                PlanTaskView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion active = versions.findActiveVersion(incidentKey)
                            .orElseThrow(() -> ApiException.conflict("尚无已发布的方案版本"));
                    PlanTask task = versions.findTask(active.id(), stableTaskId)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + stableTaskId));
                    var execution = versions.findExecution(incidentKey, stableTaskId);
                    if (execution.isPresent()) {
                        throw ApiException.conflict("任务已处于执行状态 "
                                + execution.get().status() + "，不能重新开始");
                    }
                    if (task.assignee() == null) {
                        throw ApiException.conflict("任务未指派负责人，不能开始执行: " + stableTaskId);
                    }
                    List<String> unsatisfied = unsatisfiedPrerequisites(incidentKey,
                            active.id(), stableTaskId);
                    if (!unsatisfied.isEmpty()) {
                        throw ApiException.conflict("存在未完成的前置任务: "
                                + String.join(",", unsatisfied), List.copyOf(unsatisfied));
                    }
                    Instant now = now();
                    versions.insertExecution(new PlanTaskExecution(0L, incidentKey, stableTaskId,
                            PlanTaskStatus.IN_PROGRESS, task.assignee(), actor, now,
                            null, null, now));
                    return toTaskView(versions.findTask(active.id(), stableTaskId).orElseThrow(),
                            versions.findExecution(incidentKey, stableTaskId).orElse(null));
                });
    }

    /**
     * 完成执行中的任务：仅当前指挥人；仅 IN_PROGRESS 可完成，
     * 记录完成人与 UTC 时刻，完成事实不可回退。
     */
    @Transactional
    public PlanTaskView completePlanTask(String incidentKey, String actor, String taskId,
                                         TaskCompleteRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        String stableTaskId = requireText(taskId, "taskId");
        Incident incident = lockIncident(incidentKey);
        return runIdempotent(commandKey, "plan_task_complete",
                hash(incidentKey, actor, stableTaskId), PlanTaskView.class, () -> {
                    requireCommander(incident, actor);
                    requireNotClosed(incident);
                    PlanVersion active = versions.findActiveVersion(incidentKey)
                            .orElseThrow(() -> ApiException.conflict("尚无已发布的方案版本"));
                    PlanTask task = versions.findTask(active.id(), stableTaskId)
                            .orElseThrow(() -> ApiException.notFound("任务不存在: " + stableTaskId));
                    PlanTaskExecution execution = versions.findExecution(incidentKey, stableTaskId)
                            .orElseThrow(() -> ApiException.conflict("任务尚未开始执行: "
                                    + stableTaskId));
                    if (execution.status() != PlanTaskStatus.IN_PROGRESS) {
                        throw ApiException.conflict("任务已处于状态 " + execution.status()
                                + "，不能完成");
                    }
                    Instant now = now();
                    if (versions.completeExecution(incidentKey, stableTaskId, actor, now) == 0) {
                        throw ApiException.conflict("任务执行状态已被并发变更，不能完成");
                    }
                    return toTaskView(task, versions.findExecution(incidentKey, stableTaskId)
                            .orElseThrow());
                });
    }

    /**
     * 查询当前活动方案版本（含任务执行态与依赖边）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanVersionView getActivePlan(String incidentKey) {
        requireIncident(incidentKey);
        PlanVersion active = versions.findActiveVersion(incidentKey)
                .orElseThrow(() -> ApiException.notFound("尚无已发布的方案版本: " + incidentKey));
        return toVersionView(active);
    }

    /**
     * 查询指定方案版本（含任务与边，按稳定键排序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanVersionView getVersion(String incidentKey, long versionId) {
        requireIncident(incidentKey);
        return toVersionView(requireVersion(incidentKey, versionId));
    }

    /**
     * 查询合并证据：冻结的三方差异、全部冲突解决、最终任务集与边集。
     * 只读，稳定排序，不隐式写入。
     */
    @Transactional(readOnly = true)
    public MergeEvidenceView getMergeEvidence(String incidentKey, String mergeKey) {
        requireIncident(incidentKey);
        PlanMergeRecord record = merges.findByMergeKey(mergeKey)
                .filter(r -> r.incidentKey().equals(incidentKey))
                .orElseThrow(() -> ApiException.notFound("合并记录不存在: " + mergeKey));
        return new MergeEvidenceView(record.mergeKey(), record.requestId(), record.incidentKey(),
                record.baseVersionId(), record.leftVersionId(), record.rightVersionId(),
                record.resultVersionId(),
                fromJson(record.diffJson(), PlanDiffView.class),
                fromJson(record.resolutionsJson(), new TypeReference<List<MergeResolutionItem>>() {
                }),
                fromJson(record.finalTasksJson(), new TypeReference<List<PlanTaskView>>() {
                }),
                fromJson(record.finalEdgesJson(), new TypeReference<List<PlanEdgeView>>() {
                }),
                record.createdBy(), record.createdAt());
    }

    // ---------- 合并内部步骤 ----------

    /**
     * 加载三方版本并运行合并引擎（只读计算）。
     */
    private MergeOutcome computeOutcome(String incidentKey, long baseVersionId,
                                        long leftVersionId, long rightVersionId) {
        PlanVersion base = requireVersion(incidentKey, baseVersionId);
        PlanVersion left = requireVersion(incidentKey, leftVersionId);
        PlanVersion right = requireVersion(incidentKey, rightVersionId);
        return PlanMergeEngine.compute(incidentKey,
                taskFieldsOf(base.id()), taskFieldsOf(left.id()), taskFieldsOf(right.id()),
                edgeKeys(versions.listEdges(base.id())),
                edgeKeys(versions.listEdges(left.id())),
                edgeKeys(versions.listEdges(right.id())));
    }

    private static void requireBranch(PlanVersion base, PlanVersion branch, long expected,
                                      String side) {
        if (branch.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict(side + "分支已不是 DRAFT（已并入或已发布）: "
                    + branch.id());
        }
        if (branch.baseVersionId() == null || branch.baseVersionId() != base.id()) {
            throw ApiException.conflict(side + "分支的基准版本与合并基准不一致: " + branch.id());
        }
        if (branch.revision() != expected) {
            throw ApiException.conflict(side + "分支已变化：expectedVersion=" + expected
                    + "，当前 revision=" + branch.revision());
        }
    }

    /**
     * 合并后态统一校验：不得成环、不得引用不存在任务；已 COMPLETED 任务的
     * 状态与完成事实不可回退；执行中任务不得更换负责人或新增未满足前置依赖；
     * 跨事件边仍须目标事件存在且目标任务在其当前活动版本内。违规整次 422。
     */
    private void validateMergedState(String incidentKey, long baseVersionId, ResolvedPlan plan) {
        Map<String, PlanTaskExecution> executions = new HashMap<>();
        for (PlanTaskExecution execution : versions.listExecutions(incidentKey)) {
            executions.put(execution.taskId(), execution);
        }
        // 执行事实不可回退
        for (PlanTaskExecution execution : executions.values()) {
            TaskFields merged = plan.tasks().get(execution.taskId());
            if (execution.status() == PlanTaskStatus.COMPLETED && merged == null) {
                throw ApiException.illegalTransition("已完成任务不可被合并移除: "
                        + execution.taskId());
            }
            if (execution.status() == PlanTaskStatus.IN_PROGRESS) {
                if (merged == null) {
                    throw ApiException.illegalTransition("执行中任务不可被合并移除: "
                            + execution.taskId());
                }
                if (!java.util.Objects.equals(merged.assignee(), execution.assignee())) {
                    throw ApiException.illegalTransition("执行中任务不可更换负责人: "
                            + execution.taskId());
                }
            }
        }
        // 边端点引用校验
        for (EdgeKey edge : plan.edges()) {
            if (!plan.tasks().containsKey(edge.fromTaskId())) {
                throw ApiException.illegalTransition("依赖边引用了不存在的任务: "
                        + edge.canonical());
            }
            if (edge.toIncidentKey().equals(incidentKey)) {
                if (!plan.tasks().containsKey(edge.toTaskId())) {
                    throw ApiException.illegalTransition("依赖边引用了不存在的任务: "
                            + edge.canonical());
                }
            } else {
                Incident target = incidents.findByKey(edge.toIncidentKey())
                        .orElseThrow(() -> ApiException.illegalTransition(
                                "跨事件边的目标事件不存在: " + edge.toIncidentKey()));
                PlanVersion targetActive = versions.findActiveVersion(target.incidentKey())
                        .orElseThrow(() -> ApiException.illegalTransition(
                                "跨事件边目标事件没有活动方案版本: " + edge.toIncidentKey()));
                if (versions.findTask(targetActive.id(), edge.toTaskId()).isEmpty()) {
                    throw ApiException.illegalTransition("跨事件边引用了目标事件活动版本中"
                            + "不存在的任务: " + edge.canonical());
                }
            }
        }
        // 执行中任务不得新增未满足前置依赖
        Set<EdgeKey> baseEdges = edgeKeys(versions.listEdges(baseVersionId));
        for (EdgeKey edge : plan.edges()) {
            if (baseEdges.contains(edge)) {
                continue;
            }
            PlanTaskExecution execution = executions.get(edge.fromTaskId());
            if (execution == null || execution.status() != PlanTaskStatus.IN_PROGRESS) {
                continue;
            }
            if (!isCompleted(edge.toIncidentKey(), edge.toTaskId(), incidentKey, executions)) {
                throw ApiException.illegalTransition("执行中任务不得新增未满足的前置依赖: "
                        + edge.canonical());
            }
        }
        // 全局环检测（持有图锁，与其他发布/创建串行）
        taskGraph.lockGraph();
        if (hasCycle(incidentKey, plan.edges())) {
            throw ApiException.illegalTransition("合并后的依赖图存在环，整次合并被拒绝");
        }
    }

    private boolean isCompleted(String toIncidentKey, String toTaskId, String incidentKey,
                                Map<String, PlanTaskExecution> localExecutions) {
        if (toIncidentKey.equals(incidentKey)) {
            PlanTaskExecution execution = localExecutions.get(toTaskId);
            return execution != null && execution.status() == PlanTaskStatus.COMPLETED;
        }
        return versions.findExecution(toIncidentKey, toTaskId)
                .map(e -> e.status() == PlanTaskStatus.COMPLETED).orElse(false);
    }

    /**
     * 任务未满足前置列表：活动版本中该任务的全部前置任务未 COMPLETED 者。
     */
    private List<String> unsatisfiedPrerequisites(String incidentKey, long activeVersionId,
                                                  String taskId) {
        List<String> unsatisfied = new ArrayList<>();
        Map<String, PlanTaskExecution> executions = new HashMap<>();
        for (PlanTaskExecution execution : versions.listExecutions(incidentKey)) {
            executions.put(execution.taskId(), execution);
        }
        for (PlanEdge edge : versions.listEdges(activeVersionId)) {
            if (!edge.fromTaskId().equals(taskId)) {
                continue;
            }
            if (!isCompleted(edge.toIncidentKey(), edge.toTaskId(), incidentKey, executions)) {
                unsatisfied.add(edge.toIncidentKey() + "/" + edge.toTaskId());
            }
        }
        return unsatisfied;
    }

    // ---------- 图校验 ----------

    /**
     * 全局环检测：以各事件当前活动版本边集构图（本事件用给定边集替换），
     * 检测是否存在有向环。调用前必须已持有依赖图全局锁。
     */
    private boolean hasCycle(String incidentKey, Set<EdgeKey> finalEdges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (GlobalEdge edge : versions.listActiveEdgesGlobally()) {
            if (edge.sourceIncidentKey().equals(incidentKey)) {
                continue; // 本事件以最终边集为准
            }
            adjacency.computeIfAbsent(node(edge.sourceIncidentKey(), edge.fromTaskId()),
                    k -> new ArrayList<>()).add(node(edge.toIncidentKey(), edge.toTaskId()));
        }
        for (EdgeKey edge : finalEdges) {
            adjacency.computeIfAbsent(node(incidentKey, edge.fromTaskId()),
                    k -> new ArrayList<>()).add(node(edge.toIncidentKey(), edge.toTaskId()));
        }
        return hasCycleIn(adjacency);
    }

    /**
     * 草稿内环检测：仅就草稿边集（内部边）构图检测。
     */
    private boolean hasLocalCycle(Set<EdgeKey> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (EdgeKey edge : edges) {
            adjacency.computeIfAbsent(node("", edge.fromTaskId()), k -> new ArrayList<>())
                    .add(node("", edge.toTaskId()));
        }
        return hasCycleIn(adjacency);
    }

    private static boolean hasCycleIn(Map<String, List<String>> adjacency) {
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        for (String start : adjacency.keySet()) {
            if (done.contains(start)) {
                continue;
            }
            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);
            visiting.add(start);
            while (!stack.isEmpty()) {
                String current = stack.peek();
                boolean pushed = false;
                for (String next : adjacency.getOrDefault(current, List.of())) {
                    if (visiting.contains(next)) {
                        return true;
                    }
                    if (!done.contains(next)) {
                        visiting.add(next);
                        stack.push(next);
                        pushed = true;
                        break;
                    }
                }
                if (!pushed) {
                    visiting.remove(current);
                    done.add(current);
                    stack.pop();
                }
            }
        }
        return false;
    }

    private static String node(String incidentKey, String taskId) {
        return incidentKey + NODE_SEP + taskId;
    }

    // ---------- 请求解析与校验 ----------

    private Map<String, TaskFields> parseTaskInputs(List<PlanTaskInput> inputs) {
        Map<String, TaskFields> tasks = new TreeMap<>();
        for (PlanTaskInput input : inputs) {
            String taskId = requireText(input.taskId(), "taskId");
            String title = requireText(input.title(), "title");
            String assignee = input.assignee() == null || input.assignee().isBlank()
                    ? null : input.assignee().strip();
            if (tasks.put(taskId, new TaskFields(title, assignee)) != null) {
                throw ApiException.badRequest("任务 id 重复: " + taskId);
            }
        }
        return tasks;
    }

    private Set<EdgeKey> parseEdgeInputs(String incidentKey, List<PlanEdgeInput> inputs) {
        Set<EdgeKey> edges = new HashSet<>();
        for (PlanEdgeInput input : inputs) {
            EdgeKey edge = parseEdge(incidentKey, input.fromTaskId(), input.toIncidentKey(),
                    input.toTaskId());
            if (!edges.add(edge)) {
                throw ApiException.badRequest("依赖边重复: " + edge.canonical());
            }
        }
        return edges;
    }

    private static EdgeKey parseEdge(String incidentKey, String fromTaskId, String toIncidentKey,
                                     String toTaskId) {
        String from = requireText(fromTaskId, "fromTaskId");
        String toIncident = toIncidentKey == null || toIncidentKey.isBlank()
                ? incidentKey : toIncidentKey.strip();
        String to = requireText(toTaskId, "toTaskId");
        if (toIncident.equals(incidentKey) && from.equals(to)) {
            throw ApiException.badRequest("依赖边不能指向任务自身: " + from);
        }
        return new EdgeKey(from, toIncident, to);
    }

    /**
     * 边端点存在性校验：依赖方任务须在给定任务集内；内部前置任务须在任务集内；
     * 跨事件前置任务须目标事件存在且在其当前活动版本内。
     */
    private void validateEdgeEndpoints(String incidentKey, Set<String> taskIds,
                                       Set<EdgeKey> edges) {
        for (EdgeKey edge : edges) {
            if (!taskIds.contains(edge.fromTaskId())) {
                throw ApiException.badRequest("依赖边的依赖方任务不存在: " + edge.canonical());
            }
            if (edge.toIncidentKey().equals(incidentKey)) {
                if (!taskIds.contains(edge.toTaskId())) {
                    throw ApiException.badRequest("依赖边的前置任务不存在: " + edge.canonical());
                }
            } else {
                Incident target = incidents.findByKey(edge.toIncidentKey())
                        .orElseThrow(() -> ApiException.notFound(
                                "跨事件边的目标事件不存在: " + edge.toIncidentKey()));
                PlanVersion targetActive = versions.findActiveVersion(target.incidentKey())
                        .orElseThrow(() -> ApiException.conflict(
                                "跨事件边目标事件没有活动方案版本: " + edge.toIncidentKey()));
                if (versions.findTask(targetActive.id(), edge.toTaskId()).isEmpty()) {
                    throw ApiException.notFound("跨事件边的前置任务不在目标事件活动版本内: "
                            + edge.canonical());
                }
            }
        }
    }

    /**
     * 规范化冲突解决项：字段去空白、choice 大写校验、跨事件边补默认事件键，
     * 按 conflictId 排序（换序等价）。
     */
    private static List<MergeResolutionItem> canonicalResolutions(String incidentKey,
                                                                  List<MergeResolutionItem> items) {
        List<MergeResolutionItem> canonical = new ArrayList<>();
        for (MergeResolutionItem item : items) {
            String conflictId = requireText(item.conflictId(), "conflictId");
            String choice = requireText(item.choice(), "choice");
            try {
                Choice.valueOf(choice);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("未知解决选择: " + choice);
            }
            ManualTask manualTask = item.manualTask() == null ? null
                    : new ManualTask(requireText(item.manualTask().title(), "manualTask.title"),
                            item.manualTask().assignee() == null
                                    || item.manualTask().assignee().isBlank()
                                    ? null : item.manualTask().assignee().strip());
            ManualEdge manualEdge = item.manualEdge() == null ? null
                    : new ManualEdge(requireText(item.manualEdge().fromTaskId(),
                            "manualEdge.fromTaskId"),
                            item.manualEdge().toIncidentKey() == null
                                    || item.manualEdge().toIncidentKey().isBlank()
                                    ? incidentKey : item.manualEdge().toIncidentKey().strip(),
                            requireText(item.manualEdge().toTaskId(), "manualEdge.toTaskId"));
            canonical.add(new MergeResolutionItem(conflictId, choice, manualTask, manualEdge));
        }
        canonical.sort(Comparator.comparing(MergeResolutionItem::conflictId));
        return canonical;
    }

    private static List<Resolution> parseResolutions(String incidentKey,
                                                     List<MergeResolutionItem> items) {
        return items.stream()
                .map(item -> new Resolution(item.conflictId(), Choice.valueOf(item.choice()),
                        item.manualTask() == null ? null
                                : new TaskFields(item.manualTask().title(),
                                        item.manualTask().assignee()),
                        item.manualEdge() == null ? null
                                : new EdgeKey(item.manualEdge().fromTaskId(),
                                        item.manualEdge().toIncidentKey() == null
                                                ? incidentKey : item.manualEdge().toIncidentKey(),
                                        item.manualEdge().toTaskId())))
                .toList();
    }

    // ---------- 视图组装 ----------

    private PlanVersionView toVersionView(PlanVersion version) {
        Map<String, PlanTaskExecution> executions = new HashMap<>();
        for (PlanTaskExecution execution : versions.listExecutions(version.incidentKey())) {
            executions.put(execution.taskId(), execution);
        }
        List<PlanTaskView> tasks = versions.listTasks(version.id()).stream()
                .map(t -> toTaskView(t, executions.get(t.taskId())))
                .toList();
        List<PlanEdgeView> edges = versions.listEdges(version.id()).stream()
                .map(e -> new PlanEdgeView(e.fromTaskId(), e.toIncidentKey(), e.toTaskId()))
                .toList();
        return new PlanVersionView(version.id(), version.incidentKey(), version.versionNo(),
                version.status().name(), version.baseVersionId(), version.branchSide(),
                version.revision(), version.leftVersionId(), version.rightVersionId(),
                version.createdBy(), version.createdAt(), version.publishedAt(), tasks, edges);
    }

    private static PlanTaskView toTaskView(PlanTask task, PlanTaskExecution execution) {
        if (execution == null) {
            return new PlanTaskView(task.taskId(), task.title(), task.assignee(),
                    PlanTaskStatus.PENDING.name(), null, null, null, null);
        }
        return new PlanTaskView(task.taskId(), task.title(), task.assignee(),
                execution.status().name(), execution.startedBy(), execution.startedAt(),
                execution.completedBy(), execution.completedAt());
    }

    private PlanDiffView toDiffView(long baseVersionId, long leftVersionId, long rightVersionId,
                                    MergeOutcome outcome) {
        List<TaskDiffEntry> taskChanges = outcome.taskChanges().stream()
                .map(c -> new TaskDiffEntry(c.taskId(), c.action(), fieldsView(c.base()),
                        fieldsView(c.left()), fieldsView(c.right()), fieldsView(c.adopted())))
                .toList();
        List<EdgeDiffEntry> edgeChanges = outcome.edgeChanges().stream()
                .map(c -> new EdgeDiffEntry(c.edge().canonical(), c.action(), c.adoptedBy()))
                .toList();
        List<ConflictView> conflicts = new ArrayList<>();
        for (MergeConflict conflict : outcome.conflicts()) {
            if (conflict instanceof TaskConflict taskConflict) {
                conflicts.add(new ConflictView(taskConflict.conflictId(),
                        taskConflict.type().name(), taskConflict.taskId(),
                        fieldsView(taskConflict.base()), fieldsView(taskConflict.left()),
                        fieldsView(taskConflict.right()), null, null));
            } else if (conflict instanceof EdgeConflict edgeConflict) {
                conflicts.add(new ConflictView(edgeConflict.conflictId(),
                        PlanMergeEngine.ConflictType.OPPOSITE_DIRECTION.name(), null,
                        null, null, null, edgeConflict.leftEdge().canonical(),
                        edgeConflict.rightEdge().canonical()));
            }
        }
        return new PlanDiffView(baseVersionId, leftVersionId, rightVersionId, taskChanges,
                edgeChanges, conflicts);
    }

    private static TaskFieldsView fieldsView(TaskFields fields) {
        return fields == null ? null : new TaskFieldsView(fields.title(), fields.assignee());
    }

    private MergeView toMergeView(PlanMergeRecord record) {
        PlanVersion result = versions.findVersion(record.resultVersionId()).orElseThrow();
        int taskCount = versions.listTasks(result.id()).size();
        int edgeCount = versions.listEdges(result.id()).size();
        int resolved = fromJson(record.resolutionsJson(),
                new TypeReference<List<MergeResolutionItem>>() {
                }).size();
        return new MergeView(record.mergeKey(), record.incidentKey(), record.baseVersionId(),
                record.leftVersionId(), record.rightVersionId(), record.resultVersionId(),
                result.versionNo(), result.status().name(), resolved, taskCount, edgeCount,
                record.createdAt());
    }

    // ---------- 数据装配 ----------

    private void insertTasks(long versionId, Map<String, TaskFields> tasks, Instant now) {
        for (Map.Entry<String, TaskFields> entry : tasks.entrySet()) {
            versions.insertTask(new PlanTask(0L, versionId, entry.getKey(),
                    entry.getValue().title(), entry.getValue().assignee(), now, now));
        }
    }

    private void insertEdges(long versionId, Set<EdgeKey> edges, Instant now) {
        edges.stream().sorted(Comparator.comparing(EdgeKey::canonical)).forEach(edge ->
                versions.insertEdge(new PlanEdge(0L, versionId, edge.fromTaskId(),
                        edge.toIncidentKey(), edge.toTaskId(), now)));
    }

    private Map<String, TaskFields> taskFieldsOf(long versionId) {
        Map<String, TaskFields> tasks = new TreeMap<>();
        for (PlanTask task : versions.listTasks(versionId)) {
            tasks.put(task.taskId(), new TaskFields(task.title(), task.assignee()));
        }
        return tasks;
    }

    private Set<String> draftTaskIds(long versionId) {
        Set<String> ids = new HashSet<>();
        for (PlanTask task : versions.listTasks(versionId)) {
            ids.add(task.taskId());
        }
        return ids;
    }

    private static Set<EdgeKey> edgeKeys(List<PlanEdge> edges) {
        Set<EdgeKey> keys = new HashSet<>();
        for (PlanEdge edge : edges) {
            keys.add(new EdgeKey(edge.fromTaskId(), edge.toIncidentKey(), edge.toTaskId()));
        }
        return keys;
    }

    private static String canonicalTasks(Map<String, TaskFields> tasks) {
        StringBuilder sb = new StringBuilder();
        tasks.forEach((id, fields) -> sb.append(id).append('=').append(fields.title())
                .append(':').append(fields.assignee()).append(';'));
        return sb.toString();
    }

    private static String canonicalEdges(Set<EdgeKey> edges) {
        return edges.stream().map(EdgeKey::canonical).sorted()
                .reduce((a, b) -> a + ";" + b).orElse("");
    }

    // ---------- 通用约束与幂等 ----------

    private Incident lockIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private Incident requireIncident(String incidentKey) {
        requireText(incidentKey, "incidentKey");
        return incidents.findByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
    }

    private PlanVersion requireVersion(String incidentKey, long versionId) {
        return versions.findVersion(versionId)
                .filter(v -> v.incidentKey().equals(incidentKey))
                .orElseThrow(() -> ApiException.notFound("方案版本不存在: " + versionId));
    }

    private PlanVersion requireDraft(String incidentKey, long versionId) {
        PlanVersion version = requireVersion(incidentKey, versionId);
        if (version.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict("版本不是可编辑草稿（当前状态 " + version.status() + "）: "
                    + versionId);
        }
        return version;
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

    private static long requireId(Long value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value;
    }

    private static String hash(String incidentKey, String actor, Object... parts) {
        StringBuilder sb = new StringBuilder(incidentKey).append(SEP).append(actor);
        for (Object part : parts) {
            sb.append(SEP).append(part);
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；
     * 业务失败事务回滚，不占键。
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
            throw ApiException.conflict("requestId/commandKey 已被不同参数的请求使用: "
                    + record.commandKey());
        }
        if (record.responseBody() == null) {
            throw ApiException.conflict("requestId/commandKey 正在处理中: " + record.commandKey());
        }
        try {
            return objectMapper.readValue(record.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }
}
