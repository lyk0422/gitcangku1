package com.example.starter.incident.plan.dto;

import java.util.List;

/**
 * 方案合并写接口请求体集合。requestId/commandKey 为调用方幂等键；
 * mergeKey 为合并业务键（全局唯一）；expectedVersion 为草稿修订计数快照。
 */
public final class PlanRequests {

    private PlanRequests() {
    }

    /** 任务规划字段输入：taskId 为稳定任务 id；assignee 可空表示未指派。 */
    public record PlanTaskInput(String taskId, String title, String assignee) {
    }

    /**
     * 依赖边输入：fromTaskId 依赖方（本事件任务）→ toIncidentKey/toTaskId 前置任务；
     * toIncidentKey 为空时默认本事件（内部边）。
     */
    public record PlanEdgeInput(String fromTaskId, String toIncidentKey, String toTaskId) {
    }

    /** 初始方案版本创建请求：创建即发布为活动版本。 */
    public record PlanVersionCreateRequest(String requestId, List<PlanTaskInput> tasks,
                                           List<PlanEdgeInput> edges) {
    }

    /** DRAFT 分支创建请求：从当前活动 PUBLISHED 版本复制任务与边。 */
    public record BranchCreateRequest(String requestId) {
    }

    /** 草稿任务写入（存在即改、不存在即增）：title 非空，assignee 可空。 */
    public record DraftTaskRequest(String title, String assignee) {
    }

    /** 草稿边增删请求：toIncidentKey 为空时默认本事件。 */
    public record DraftEdgeRequest(String fromTaskId, String toIncidentKey, String toTaskId) {
    }

    /** MANUAL 任务解决：给出完整任务字段（任务保留为这些字段）。 */
    public record ManualTask(String title, String assignee) {
    }

    /** MANUAL 边解决：给出最终边；为 null 表示该冲突最终无边。 */
    public record ManualEdge(String fromTaskId, String toIncidentKey, String toTaskId) {
    }

    /**
     * 冲突解决项：conflictId 来自差异查询；choice 为 LEFT / RIGHT / MANUAL；
     * MANUAL 时任务冲突须给 manualTask、边冲突须给 manualEdge（可为 null 表示无边）。
     */
    public record MergeResolutionItem(String conflictId, String choice,
                                      ManualTask manualTask, ManualEdge manualEdge) {
    }

    /**
     * 合并请求：一次提交全部冲突的解决；left/rightExpectedVersion 为两侧草稿修订快照，
     * 任一分支变化、活动版本已前进或完整后态违规，整次 409/422 且不生成合并版本。
     */
    public record MergeRequest(String requestId, String mergeKey,
                               Long baseVersionId, Long leftVersionId, Long rightVersionId,
                               Long leftExpectedVersion, Long rightExpectedVersion,
                               List<MergeResolutionItem> resolutions) {
    }

    /** 任务开始请求：commandKey 幂等；任务须已指派规划负责人且前置全部完成。 */
    public record TaskStartRequest(String commandKey) {
    }

    /** 任务完成请求：commandKey 幂等；仅 IN_PROGRESS 可完成。 */
    public record TaskCompleteRequest(String commandKey) {
    }
}
