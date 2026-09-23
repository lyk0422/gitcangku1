package com.example.starter.incident.plan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.CommandKeyRecord;
import com.example.starter.incident.CommandKeyRepository;
import com.example.starter.incident.Incident;
import com.example.starter.incident.IncidentRepository;
import com.example.starter.incident.IncidentStatus;
import com.example.starter.incident.dto.Requests.PlanCreateRequest;
import com.example.starter.incident.dto.Requests.PlanEdgeInput;
import com.example.starter.incident.dto.Requests.PlanTaskActionRequest;
import com.example.starter.incident.dto.Requests.PlanTaskInput;
import com.example.starter.incident.dto.Requests.RevisionCreateRequest;
import com.example.starter.incident.dto.Requests.RevisionUpdateRequest;
import com.example.starter.incident.dto.Responses.DiffView;
import com.example.starter.incident.dto.Responses.PlanEdgeView;
import com.example.starter.incident.dto.Responses.PlanTaskView;
import com.example.starter.incident.dto.Responses.PlanVersionView;
import com.example.starter.incident.dto.Responses.PlanView;
import com.example.starter.incident.plan.ThreeWayMerge.Outcome;
import com.example.starter.incident.plan.ThreeWayMerge.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 处置方案核心服务：方案创建、草稿修订、版本/差异查询与活动版本任务执行。
 * 并发约定：所有写接口先 SELECT ... FOR UPDATE 锁定方案行，同事务内完成校验与写入，
 * 使合并发布、任务执行与草稿变更按事务提交顺序生效。
 * 幂等约定：任务执行复用 commandKey 幂等键（同键同参重放首次响应，同键改参 409，失败不占键）。
 */
@Service
public class PlanService {

    private static final String SEP = "\\u001F";

    private final PlanRepository plans;
    private final IncidentRepository incidents;
    private final CommandKeyRepository commandKeys;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlanService(PlanRepository plans, IncidentRepository incidents,
                       CommandKeyRepository commandKeys, ObjectMapper objectMapper, Clock clock) {
        this.plans = plans;
        this.incidents = incidents;
        this.commandKeys = commandKeys;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 创建方案：同时建立空的初始 PUBLISHED 版本（版本号 1）并置为活动版本。
     * planKey 重复返回 409。
     */
    @Transactional
    public PlanView createPlan(String actor, PlanCreateRequest req) {
        String planKey = requireText(req.planKey(), "planKey");
        if (plans.findPlanByKey(planKey).isPresent()) {
            throw ApiException.conflict("planKey 已存在: " + planKey);
        }
        Instant now = now();
        long planId;
        try {
            planId = plans.insertPlan(planKey, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("planKey 已存在: " + planKey);
        }
        long versionId = plans.insertVersion(new PlanVersion(0L, planId, 1,
                PlanVersionStatus.PUBLISHED, null, 1, actor, now));
        plans.updateActiveVersion(planId, versionId);
        return toPlanView(plans.findPlanByKey(planKey).orElseThrow());
    }

    /**
     * 查询方案当前视图（含活动版本号）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanView getPlan(String planKey) {
        return toPlanView(findPlan(planKey));
    }

    /**
     * 从当前活动 PUBLISHED 版本创建 DRAFT 修订：完整复制任务集与边集，
     * 草稿乐观锁序号从 1 开始。baseVersion 非活动 PUBLISHED 版本返回 409。
     */
    @Transactional
    public PlanVersionView createRevision(String planKey, String actor, RevisionCreateRequest req) {
        if (req.baseVersion() == null) {
            throw ApiException.badRequest("baseVersion 不能为空");
        }
        Plan plan = lockPlan(planKey);
        PlanVersion base = plans.findVersion(plan.id(), req.baseVersion().intValue())
                .orElseThrow(() -> ApiException.notFound(
                        "baseVersion 不存在: " + req.baseVersion()));
        if (base.status() != PlanVersionStatus.PUBLISHED
                || !Long.valueOf(base.id()).equals(plan.activeVersionId())) {
            throw ApiException.conflict("baseVersion 须为当前活动 PUBLISHED 版本: "
                    + req.baseVersion());
        }
        int versionNo = plans.maxVersionNo(plan.id()) + 1;
        long draftId = plans.insertVersion(new PlanVersion(0L, plan.id(), versionNo,
                PlanVersionStatus.DRAFT, base.id(), 1, actor, now()));
        plans.replaceTasks(draftId, plans.listTasks(base.id()).stream()
                .map(PlanTask::content).toList());
        plans.replaceEdges(draftId, plans.listEdges(base.id()).stream()
                .map(PlanEdge::key).toList());
        return toVersionView(plans.findVersionById(draftId).orElseThrow());
    }

    /**
     * 整体替换草稿任务集与边集：expectedVersion 须与当前一致（否则 409）；
     * 仅 DRAFT 可修改，PUBLISHED/MERGED 不可变（409）。成功后乐观锁序号加一。
     */
    @Transactional
    public PlanVersionView updateRevision(String planKey, int versionNo, String actor,
                                          RevisionUpdateRequest req) {
        if (req.expectedVersion() == null) {
            throw ApiException.badRequest("expectedVersion 不能为空");
        }
        List<TaskContent> tasks = validateTasks(req.tasks());
        List<EdgeKey> edges = validateEdges(req.edges(), tasks);
        Plan plan = lockPlan(planKey);
        PlanVersion version = plans.findVersion(plan.id(), versionNo)
                .orElseThrow(() -> ApiException.notFound("版本不存在: " + versionNo));
        if (version.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict("版本状态为 " + version.status() + "，不可修改");
        }
        if (version.expectedVersion() != req.expectedVersion()) {
            throw ApiException.conflict("expectedVersion 不匹配: 当前 "
                    + version.expectedVersion() + "，提交 " + req.expectedVersion());
        }
        if (plans.bumpExpectedVersion(version.id(), req.expectedVersion().intValue()) == 0) {
            throw ApiException.conflict("草稿已被并发修改，请重新读取 expectedVersion");
        }
        plans.replaceTasks(version.id(), tasks);
        plans.replaceEdges(version.id(), edges);
        return toVersionView(plans.findVersionById(version.id()).orElseThrow());
    }

    /**
     * 查询版本详情（任务集与边集，稳定排序）。只读，不隐式写入。
     */
    @Transactional(readOnly = true)
    public PlanVersionView getVersion(String planKey, int versionNo) {
        Plan plan = findPlan(planKey);
        PlanVersion version = plans.findVersion(plan.id(), versionNo)
                .orElseThrow(() -> ApiException.notFound("版本不存在: " + versionNo));
        return toVersionView(version);
    }

    /**
     * 三方差异查询：按稳定 taskId 与边键比较 base/left/right 三份版本快照，
     * 返回自动采用项与全部显式冲突（含 conflictKey，供合并提交解决）。只读、稳定排序。
     */
    @Transactional(readOnly = true)
    public DiffView diff(String planKey, int baseVersion, int leftVersion, int rightVersion) {
        Plan plan = findPlan(planKey);
        Snapshot base = snapshot(plan.id(), baseVersion);
        Snapshot left = snapshot(plan.id(), leftVersion);
        Snapshot right = snapshot(plan.id(), rightVersion);
        Outcome outcome = ThreeWayMerge.merge(base, left, right);
        return PlanViews.toDiffView(baseVersion, leftVersion, rightVersion, outcome);
    }

    /**
     * 开始执行活动版本任务：仅当前指挥人；仅 PENDING 可开始；
     * 全部前置任务 COMPLETED 后才可开始，否则 409 并返回未满足前置列表。
     */
    @Transactional
    public PlanTaskView startTask(String planKey, String taskId, String actor,
                                  PlanTaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Plan plan = lockPlan(planKey);
        return runIdempotent(commandKey, "plan_task_start", hash(planKey, taskId, actor),
                PlanTaskView.class, () -> {
                    PlanTask task = activeTask(plan, taskId);
                    requireIncidentCommander(task.incidentKey(), actor);
                    if (task.status() != PlanTaskStatus.PENDING) {
                        throw ApiException.conflict(
                                "任务状态为 " + task.status() + "，不能开始执行");
                    }
                    List<String> unmet = unmetPredecessors(plan.activeVersionId(), taskId);
                    if (!unmet.isEmpty()) {
                        throw ApiException.conflict("存在未完成的前置任务: "
                                + String.join(",", unmet), List.copyOf(unmet));
                    }
                    if (plans.updateTaskStatus(plan.activeVersionId(), taskId,
                            PlanTaskStatus.PENDING, PlanTaskStatus.IN_PROGRESS, null, null) == 0) {
                        throw ApiException.conflict("任务已被并发修改，不能开始执行");
                    }
                    return toTaskView(activeTask(plan, taskId));
                });
    }

    /**
     * 完成活动版本任务：仅当前指挥人；仅 IN_PROGRESS 可完成；
     * 记录完成人与 UTC 完成时刻（完成事实不可回退）。
     */
    @Transactional
    public PlanTaskView completeTask(String planKey, String taskId, String actor,
                                     PlanTaskActionRequest req) {
        String commandKey = requireText(req.commandKey(), "commandKey");
        Plan plan = lockPlan(planKey);
        return runIdempotent(commandKey, "plan_task_complete", hash(planKey, taskId, actor),
                PlanTaskView.class, () -> {
                    PlanTask task = activeTask(plan, taskId);
                    requireIncidentCommander(task.incidentKey(), actor);
                    if (task.status() != PlanTaskStatus.IN_PROGRESS) {
                        throw ApiException.conflict(
                                "任务状态为 " + task.status() + "，不能完成");
                    }
                    if (plans.updateTaskStatus(plan.activeVersionId(), taskId,
                            PlanTaskStatus.IN_PROGRESS, PlanTaskStatus.COMPLETED,
                            actor, now()) == 0) {
                        throw ApiException.conflict("任务已被并发修改，不能完成");
                    }
                    return toTaskView(activeTask(plan, taskId));
                });
    }

    // ---------- 供合并发布复用的内部方法 ----------

    /**
     * 锁定方案行（SELECT ... FOR UPDATE），不存在返回 404。
     */
    Plan lockPlan(String planKey) {
        requireText(planKey, "planKey");
        return plans.lockPlanByKey(planKey)
                .orElseThrow(() -> ApiException.notFound("方案不存在: " + planKey));
    }

    /**
     * 加载版本快照（任务集 + 边集）。
     */
    Snapshot snapshot(long planId, int versionNo) {
        PlanVersion version = plans.findVersion(planId, versionNo)
                .orElseThrow(() -> ApiException.notFound("版本不存在: " + versionNo));
        return PlanViews.snapshot(plans.listTasks(version.id()), plans.listEdges(version.id()));
    }

    /**
     * 校验并转换任务输入：字段非空、状态合法、COMPLETED 须带完成事实、taskId 不重复。
     */
    List<TaskContent> validateTasks(List<PlanTaskInput> inputs) {
        if (inputs == null) {
            return List.of();
        }
        Map<String, TaskContent> tasks = new LinkedHashMap<>();
        for (PlanTaskInput input : inputs) {
            TaskContent task = toTaskContent(input);
            if (tasks.put(task.taskId(), task) != null) {
                throw ApiException.badRequest("taskId 重复: " + task.taskId());
            }
        }
        return List.copyOf(tasks.values());
    }

    /**
     * 校验并转换边输入：端点非空、不得自环、不得重复、端点必须存在于任务集。
     */
    List<EdgeKey> validateEdges(List<PlanEdgeInput> inputs, List<TaskContent> tasks) {
        if (inputs == null) {
            return List.of();
        }
        Map<String, String> taskIds = new LinkedHashMap<>();
        tasks.forEach(t -> taskIds.put(t.taskId(), t.taskId()));
        List<EdgeKey> edges = new ArrayList<>();
        var seen = new java.util.HashSet<String>();
        for (PlanEdgeInput input : inputs) {
            String from = requireText(input.fromTaskId(), "fromTaskId");
            String to = requireText(input.toTaskId(), "toTaskId");
            if (from.equals(to)) {
                throw ApiException.badRequest("依赖边不能自环: " + from);
            }
            if (!taskIds.containsKey(from) || !taskIds.containsKey(to)) {
                throw ApiException.badRequest("依赖边引用不存在的任务: " + from + "->" + to);
            }
            if (!seen.add(from + SEP + to)) {
                throw ApiException.badRequest("依赖边重复: " + from + "->" + to);
            }
            edges.add(new EdgeKey(from, to));
        }
        return List.copyOf(edges);
    }

    /**
     * 校验并转换单个任务输入为任务内容（MANUAL 解决与草稿替换共用）。
     */
    TaskContent toTaskContent(PlanTaskInput input) {
        if (input == null) {
            throw ApiException.badRequest("任务字段不能为空");
        }
        String taskId = requireText(input.taskId(), "taskId");
        String incidentKey = requireText(input.incidentKey(), "incidentKey");
        String groupCode = requireText(input.groupCode(), "groupCode");
        String title = requireText(input.title(), "title");
        String assignee = requireText(input.assignee(), "assignee");
        PlanTaskStatus status;
        try {
            status = PlanTaskStatus.valueOf(requireText(input.status(), "status"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知任务状态: " + input.status());
        }
        if (status == PlanTaskStatus.COMPLETED) {
            requireText(input.completedBy(), "completedBy");
            if (input.completedAt() == null) {
                throw ApiException.badRequest("completedAt 不能为空（COMPLETED）");
            }
        } else if (input.completedBy() != null || input.completedAt() != null) {
            throw ApiException.badRequest("completedBy/completedAt 仅 COMPLETED 可有值");
        }
        return new TaskContent(taskId, incidentKey, groupCode, title, assignee, status,
                input.completedBy(), input.completedAt());
    }

    /**
     * 校验操作人为事件当前指挥人且事件未关闭（既有权限与状态规则）。
     */
    void requireIncidentCommander(String incidentKey, String actor) {
        Incident incident = incidents.lockByKey(incidentKey)
                .orElseThrow(() -> ApiException.notFound("事件不存在: " + incidentKey));
        if (incident.status() == IncidentStatus.CLOSED) {
            throw ApiException.illegalTransition("事件已关闭，不能执行方案操作: " + incidentKey);
        }
        if (incident.commander() == null || !incident.commander().equals(actor)) {
            throw ApiException.conflict("只有事件 " + incidentKey + " 的当前指挥人 "
                    + (incident.commander() == null ? "(无)" : incident.commander())
                    + " 能执行该操作");
        }
    }

    // ---------- 私有辅助 ----------

    private Plan findPlan(String planKey) {
        requireText(planKey, "planKey");
        return plans.findPlanByKey(planKey)
                .orElseThrow(() -> ApiException.notFound("方案不存在: " + planKey));
    }

    private PlanTask activeTask(Plan plan, String taskId) {
        requireText(taskId, "taskId");
        return plans.findTask(plan.activeVersionId(), taskId)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskId));
    }

    /**
     * 未满足前置任务列表：活动版本中存在 pred → taskId 边且 pred 未 COMPLETED。
     */
    private List<String> unmetPredecessors(long versionId, String taskId) {
        return plans.listEdges(versionId).stream()
                .filter(e -> e.toTaskId().equals(taskId))
                .map(PlanEdge::fromTaskId)
                .distinct()
                .sorted()
                .filter(pred -> plans.findTask(versionId, pred)
                        .map(t -> t.status() != PlanTaskStatus.COMPLETED)
                        .orElse(true))
                .toList();
    }

    private PlanView toPlanView(Plan plan) {
        Long activeVersion = null;
        if (plan.activeVersionId() != null) {
            activeVersion = plans.findVersionById(plan.activeVersionId())
                    .map(v -> (long) v.versionNo()).orElse(null);
        }
        return new PlanView(plan.planKey(), activeVersion, plan.createdAt());
    }

    private PlanVersionView toVersionView(PlanVersion version) {
        List<PlanTaskView> tasks = plans.listTasks(version.id()).stream()
                .map(PlanTask::content).map(PlanViews::toTaskView).toList();
        List<PlanEdgeView> edges = plans.listEdges(version.id()).stream()
                .map(PlanEdge::key).map(PlanViews::toEdgeView).toList();
        Long baseVersion = null;
        if (version.baseVersionId() != null) {
            baseVersion = plans.findVersionById(version.baseVersionId())
                    .map(v -> (long) v.versionNo()).orElse(null);
        }
        return new PlanVersionView(version.versionNo(), version.status().name(), baseVersion,
                version.expectedVersion(), tasks, edges);
    }

    private PlanTaskView toTaskView(PlanTask task) {
        return PlanViews.toTaskView(task.content());
    }

    /**
     * 幂等执行：同键同参重放首次响应，同键改参 409；并发同键由唯一约束串行化；失败不占键。
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
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }

    static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(String.join(SEP, parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
