package com.example.starter.incident.plan;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.example.starter.incident.ApiException;
import com.example.starter.incident.dto.Requests.MergeResolutionRequest;
import com.example.starter.incident.dto.Requests.PlanEdgeInput;
import com.example.starter.incident.dto.Requests.PlanMergeRequest;
import com.example.starter.incident.dto.Responses.DiffView;
import com.example.starter.incident.dto.Responses.MergeEvidenceView;
import com.example.starter.incident.dto.Responses.MergeResolutionView;
import com.example.starter.incident.dto.Responses.PlanEdgeView;
import com.example.starter.incident.dto.Responses.PlanTaskView;
import com.example.starter.incident.plan.ThreeWayMerge.Choice;
import com.example.starter.incident.plan.ThreeWayMerge.MergedPlan;
import com.example.starter.incident.plan.ThreeWayMerge.Outcome;
import com.example.starter.incident.plan.ThreeWayMerge.Resolution;
import com.example.starter.incident.plan.ThreeWayMerge.Snapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 方案三方合并与原子发布服务。
 * 发布在单个事务内完成：锁定方案行后重新读取 base、两分支、当前活动版本及相关事件状态，
 * 任一分支变化、活动版本已前进或完整后态违规均整次失败（409/422）且不生成合并版本；
 * 成功只创建一个新的 PUBLISHED 版本，冻结三方差异、全部冲突解决、最终任务集与边集，
 * 原分支标记 MERGED 不可变。
 * 幂等约定：requestId 同参重放首次快照（冲突解决项换序等价），异参 409，失败事务回滚不占键；
 * mergeKey 全局唯一。
 */
@Service
public class PlanMergeService {

    private final PlanRepository plans;
    private final PlanMergeRepository merges;
    private final PlanService planService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PlanMergeService(PlanRepository plans, PlanMergeRepository merges,
                            PlanService planService, ObjectMapper objectMapper, Clock clock) {
        this.plans = plans;
        this.merges = merges;
        this.planService = planService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 三方合并并原子发布。冲突解决须一次覆盖全部冲突（遗漏、多余、重复均 400）。
     */
    @Transactional
    public MergeEvidenceView merge(String planKey, String actor, PlanMergeRequest req) {
        String requestId = PlanService.requireText(req.requestId(), "requestId");
        String mergeKey = PlanService.requireText(req.mergeKey(), "mergeKey");
        if (req.baseVersion() == null || req.leftVersion() == null || req.rightVersion() == null) {
            throw ApiException.badRequest("baseVersion/leftVersion/rightVersion 不能为空");
        }
        if (req.leftExpectedVersion() == null || req.rightExpectedVersion() == null) {
            throw ApiException.badRequest("leftExpectedVersion/rightExpectedVersion 不能为空");
        }
        if (req.leftVersion().equals(req.rightVersion())) {
            throw ApiException.badRequest("leftVersion 与 rightVersion 必须不同");
        }
        List<MergeResolutionRequest> resolutionRequests =
                req.resolutions() == null ? List.of() : req.resolutions();
        List<Resolution> resolutions = new ArrayList<>();
        for (MergeResolutionRequest resolutionRequest : resolutionRequests) {
            resolutions.add(toResolution(resolutionRequest));
        }
        String requestHash = PlanService.hash(planKey, mergeKey,
                String.valueOf(req.baseVersion()), String.valueOf(req.leftVersion()),
                String.valueOf(req.rightVersion()), String.valueOf(req.leftExpectedVersion()),
                String.valueOf(req.rightExpectedVersion()), canonicalResolutions(resolutions));

        Plan plan = planService.lockPlan(planKey);

        // requestId 同参重放首次快照，异参 409
        var existing = merges.findByRequestId(requestId);
        if (existing.isPresent()) {
            PlanMerge committed = existing.get();
            if (!committed.requestHash().equals(requestHash)) {
                throw ApiException.conflict("requestId 已被不同参数的请求使用: " + requestId);
            }
            return toEvidenceView(committed);
        }
        if (merges.findByMergeKey(mergeKey).isPresent()) {
            throw ApiException.conflict("mergeKey 已存在: " + mergeKey);
        }

        // 发布事务内重新读取 base、两分支与当前活动版本
        PlanVersion base = plans.findVersion(plan.id(), req.baseVersion().intValue())
                .orElseThrow(() -> ApiException.notFound("baseVersion 不存在: " + req.baseVersion()));
        PlanVersion left = plans.findVersion(plan.id(), req.leftVersion().intValue())
                .orElseThrow(() -> ApiException.notFound("leftVersion 不存在: " + req.leftVersion()));
        PlanVersion right = plans.findVersion(plan.id(), req.rightVersion().intValue())
                .orElseThrow(() -> ApiException.notFound(
                        "rightVersion 不存在: " + req.rightVersion()));
        if (base.status() != PlanVersionStatus.PUBLISHED
                || !Long.valueOf(base.id()).equals(plan.activeVersionId())) {
            throw ApiException.conflict("活动版本已前进，baseVersion 不再是当前活动 PUBLISHED 版本");
        }
        requireBranch(left, base, req.leftExpectedVersion().intValue(), "left");
        requireBranch(right, base, req.rightExpectedVersion().intValue(), "right");

        Snapshot baseSnapshot = PlanViews.snapshot(plans.listTasks(base.id()),
                plans.listEdges(base.id()));
        Snapshot leftSnapshot = PlanViews.snapshot(plans.listTasks(left.id()),
                plans.listEdges(left.id()));
        Snapshot rightSnapshot = PlanViews.snapshot(plans.listTasks(right.id()),
                plans.listEdges(right.id()));
        Outcome outcome = ThreeWayMerge.merge(baseSnapshot, leftSnapshot, rightSnapshot);
        MergedPlan merged = ThreeWayMerge.apply(outcome, resolutions);

        // 完整后态统一校验：图合法、执行事实不可回退、跨事件权限与状态规则
        String graphError = ThreeWayMerge.validateGraph(merged);
        if (graphError != null) {
            throw ApiException.illegalTransition(graphError);
        }
        validateExecutionState(merged, base);
        validateIncidentPermissions(merged, actor);

        // 成功只创建一个新的 PUBLISHED 版本，原分支标记 MERGED
        int resultVersionNo = plans.maxVersionNo(plan.id()) + 1;
        long resultVersionId = plans.insertVersion(new PlanVersion(0L, plan.id(), resultVersionNo,
                PlanVersionStatus.PUBLISHED, base.id(), 1, actor, clock.instant()));
        plans.replaceTasks(resultVersionId, List.copyOf(merged.tasks().values()));
        plans.replaceEdges(resultVersionId, List.copyOf(merged.edges()));
        plans.updateActiveVersion(plan.id(), resultVersionId);
        if (plans.markMerged(left.id()) == 0 || plans.markMerged(right.id()) == 0) {
            throw ApiException.conflict("分支已被并发处理，无法标记 MERGED");
        }

        DiffView diffView = PlanViews.toDiffView(base.versionNo(), left.versionNo(),
                right.versionNo(), outcome);
        List<MergeResolutionView> resolutionViews = toResolutionViews(resolutions);
        PlanMerge merge;
        try {
            long mergeId = merges.insert(new PlanMerge(0L, plan.id(), mergeKey, requestId,
                    requestHash, base.id(), left.id(), right.id(),
                    req.leftExpectedVersion().intValue(), req.rightExpectedVersion().intValue(),
                    resultVersionId, toJson(diffView), toJson(resolutionViews),
                    actor, clock.instant()));
            merge = merges.findByMergeKey(mergeKey).orElseThrow();
            if (merge.id() != mergeId) {
                throw new IllegalStateException("合并证据读取不一致");
            }
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("mergeKey 或 requestId 已被并发使用: " + mergeKey);
        }
        return toEvidenceView(merge);
    }

    /**
     * 合并证据查询：冻结的三方差异、全部冲突解决、最终任务集与边集。只读、稳定排序。
     */
    @Transactional(readOnly = true)
    public MergeEvidenceView getMerge(String planKey, String mergeKey) {
        Plan plan = plans.findPlanByKey(PlanService.requireText(planKey, "planKey"))
                .orElseThrow(() -> ApiException.notFound("方案不存在: " + planKey));
        PlanMerge merge = merges.findByMergeKey(mergeKey)
                .filter(m -> m.planId() == plan.id())
                .orElseThrow(() -> ApiException.notFound("合并不存在: " + mergeKey));
        return toEvidenceView(merge);
    }

    // ---------- 私有辅助 ----------

    /**
     * 校验分支草稿：仍为本 base 的 DRAFT 且 expectedVersion 与提交一致，否则 409。
     */
    private static void requireBranch(PlanVersion branch, PlanVersion base, int expected,
                                      String side) {
        if (branch.status() != PlanVersionStatus.DRAFT) {
            throw ApiException.conflict(side + " 分支已变化（状态 " + branch.status() + "），不能合并");
        }
        if (!Objects.equals(branch.baseVersionId(), base.id())) {
            throw ApiException.conflict(side + " 分支的 baseVersion 与提交不一致");
        }
        if (branch.expectedVersion() != expected) {
            throw ApiException.conflict(side + " 分支已变化: expectedVersion 当前 "
                    + branch.expectedVersion() + "，提交 " + expected);
        }
    }

    /**
     * 执行事实校验（相对当前活动版本）：已 COMPLETED 任务的状态与完成事实不可回退；
     * 正在执行任务不得更换负责人或新增未满足前置依赖。违规整次 422。
     */
    private void validateExecutionState(MergedPlan merged, PlanVersion activeVersion) {
        List<PlanTask> activeTasks = plans.listTasks(activeVersion.id());
        Set<EdgeKey> activeEdges = new java.util.HashSet<>();
        plans.listEdges(activeVersion.id()).forEach(e -> activeEdges.add(e.key()));
        for (PlanTask active : activeTasks) {
            TaskContent mergedTask = merged.tasks().get(active.taskId());
            if (active.status() == PlanTaskStatus.COMPLETED) {
                if (mergedTask == null || mergedTask.status() != PlanTaskStatus.COMPLETED
                        || !Objects.equals(mergedTask.completedBy(), active.completedBy())
                        || !Objects.equals(mergedTask.completedAt(), active.completedAt())) {
                    throw ApiException.illegalTransition(
                            "已 COMPLETED 任务的状态和完成事实不可回退: " + active.taskId());
                }
            } else if (active.status() == PlanTaskStatus.IN_PROGRESS) {
                if (mergedTask == null || mergedTask.status() != PlanTaskStatus.IN_PROGRESS) {
                    throw ApiException.illegalTransition(
                            "正在执行任务的状态不可回退: " + active.taskId());
                }
                if (!mergedTask.assignee().equals(active.assignee())) {
                    throw ApiException.illegalTransition(
                            "正在执行任务不得更换负责人: " + active.taskId());
                }
                for (EdgeKey edge : merged.edges()) {
                    if (edge.toTaskId().equals(active.taskId()) && !activeEdges.contains(edge)) {
                        TaskContent pred = merged.tasks().get(edge.fromTaskId());
                        if (pred == null || pred.status() != PlanTaskStatus.COMPLETED) {
                            throw ApiException.illegalTransition(
                                    "正在执行任务不得新增未满足前置依赖: " + active.taskId()
                                            + " 新增前置 " + edge.fromTaskId());
                        }
                    }
                }
            }
        }
    }

    /**
     * 跨事件权限与状态规则：合并后任务集涉及的每个事件必须存在、未关闭，
     * 且操作人为其当前指挥人（与既有任务规则一致）。
     */
    private void validateIncidentPermissions(MergedPlan merged, String actor) {
        Set<String> incidentKeys = new TreeSet<>();
        merged.tasks().values().forEach(t -> incidentKeys.add(t.incidentKey()));
        for (String incidentKey : incidentKeys) {
            planService.requireIncidentCommander(incidentKey, actor);
        }
    }

    /**
     * 转换一条冲突解决：choice 取值 LEFT/RIGHT/MANUAL；MANUAL 任务/边内容在此校验完整性。
     */
    private Resolution toResolution(MergeResolutionRequest req) {
        String conflictKey = PlanService.requireText(req.conflictKey(), "conflictKey");
        Choice choice;
        try {
            choice = Choice.valueOf(PlanService.requireText(req.choice(), "choice"));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知解决选择: " + req.choice());
        }
        TaskContent manualTask = req.manualTask() == null ? null
                : planService.toTaskContent(req.manualTask());
        boolean edgeSpecified = req.manualEdge() != null;
        EdgeKey manualEdge = null;
        if (edgeSpecified) {
            PlanEdgeInput edge = req.manualEdge();
            boolean fromBlank = edge.fromTaskId() == null || edge.fromTaskId().isBlank();
            boolean toBlank = edge.toTaskId() == null || edge.toTaskId().isBlank();
            if (fromBlank != toBlank) {
                throw ApiException.badRequest("manualEdge 端点必须同时给出或同时为空: "
                        + conflictKey);
            }
            if (!fromBlank) {
                manualEdge = new EdgeKey(edge.fromTaskId().strip(), edge.toTaskId().strip());
            }
        }
        return new Resolution(conflictKey, choice, manualTask, edgeSpecified, manualEdge);
    }

    /**
     * 规范化冲突解决串：按 conflictKey 排序后拼接，保证冲突项换序等价。
     */
    private static String canonicalResolutions(List<Resolution> resolutions) {
        return resolutions.stream()
                .sorted((a, b) -> a.conflictKey().compareTo(b.conflictKey()))
                .map(PlanMergeService::canonicalResolution)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String canonicalResolution(Resolution r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.conflictKey()).append('#').append(r.choice());
        TaskContent t = r.manualTask();
        if (t != null) {
            sb.append('#').append(t.taskId()).append(',').append(t.incidentKey()).append(',')
                    .append(t.groupCode()).append(',').append(t.title()).append(',')
                    .append(t.assignee()).append(',').append(t.status()).append(',')
                    .append(t.completedBy()).append(',').append(t.completedAt());
        }
        if (r.manualEdgeSpecified()) {
            sb.append('#').append(r.manualEdge() == null ? "NOEDGE"
                    : r.manualEdge().fromTaskId() + "->" + r.manualEdge().toTaskId());
        }
        return sb.toString();
    }

    private static List<MergeResolutionView> toResolutionViews(List<Resolution> resolutions) {
        return resolutions.stream()
                .sorted((a, b) -> a.conflictKey().compareTo(b.conflictKey()))
                .map(r -> new MergeResolutionView(r.conflictKey(), r.choice().name(),
                        PlanViews.toTaskView(r.manualTask()),
                        r.manualEdgeSpecified()
                                ? (r.manualEdge() == null ? null
                                        : PlanViews.toEdgeView(r.manualEdge()))
                                : null))
                .toList();
    }

    /**
     * 组装合并证据视图：冻结差异与解决来自 plan_merges，最终任务集与边集来自结果版本。
     */
    private MergeEvidenceView toEvidenceView(PlanMerge merge) {
        DiffView diff = fromJson(merge.diffJson(), DiffView.class);
        List<MergeResolutionView> resolutions = fromJson(merge.resolutionsJson(),
                new TypeReference<>() {
                });
        List<PlanTaskView> tasks = plans.listTasks(merge.resultVersionId()).stream()
                .map(PlanTask::content).map(PlanViews::toTaskView).toList();
        List<PlanEdgeView> edges = plans.listEdges(merge.resultVersionId()).stream()
                .map(PlanEdge::key).map(PlanViews::toEdgeView).toList();
        return new MergeEvidenceView(merge.mergeKey(), merge.requestId(),
                versionNoOf(merge.baseVersionId()), versionNoOf(merge.leftVersionId()),
                versionNoOf(merge.rightVersionId()), versionNoOf(merge.resultVersionId()),
                diff, resolutions, tasks, edges, merge.createdBy(), merge.createdAt());
    }

    private long versionNoOf(long versionId) {
        return plans.findVersionById(versionId)
                .orElseThrow(() -> new IllegalStateException("版本不存在: " + versionId))
                .versionNo();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("合并证据序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("合并证据反序列化失败", e);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("合并证据反序列化失败", e);
        }
    }
}
