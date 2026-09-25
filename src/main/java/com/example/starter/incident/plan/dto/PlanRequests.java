package com.example.starter.incident.plan.dto;

import java.util.List;

/**
 * 方案版本与合并写接口请求体集合。commandKey/requestId 为调用方幂等键。
 */
public final class PlanRequests {

    private PlanRequests() {
    }

    /** 计划任务字段输入：taskId 为跨版本稳定标识，assignee 为负责人。 */
    public record PlanTaskInput(String taskId, String groupCode, String title, String assignee) {
    }

    /**
     * 依赖边输入：fromTaskId 为前置任务；toIncidentKey 为空/null 表示本事件内部边，
     * 否则为跨事件边（指向目标事件活动版本中的 toTaskId）。
     */
    public record PlanEdgeInput(String fromTaskId, String toTaskId, String toIncidentKey) {
    }

    /** 首个方案版本创建并直接发布请求。 */
    public record PlanCreateRequest(String commandKey, List<PlanTaskInput> tasks,
                                    List<PlanEdgeInput> edges) {
    }

    /** 从 PUBLISHED 基版本创建 DRAFT 修订请求：branch 为 LEFT/RIGHT 分支标记。 */
    public record DraftCreateRequest(String commandKey, Integer baseVersion, String branch) {
    }

    /** 草稿任务新增/修改请求（按 taskId upsert）。 */
    public record DraftTaskRequest(String commandKey, String taskId, String groupCode,
                                   String title, String assignee) {
    }

    /** 草稿任务移除请求。 */
    public record DraftTaskRemoveRequest(String commandKey) {
    }

    /** 草稿边新增请求。 */
    public record DraftEdgeRequest(String commandKey, String fromTaskId, String toTaskId,
                                   String toIncidentKey) {
    }

    /** 草稿边移除请求。 */
    public record DraftEdgeRemoveRequest(String commandKey, String fromTaskId, String toTaskId,
                                         String toIncidentKey) {
    }

    /**
     * 冲突解决项：choice 为 LEFT/RIGHT/MANUAL；MANUAL 任务冲突须给出完整 manualTask，
     * MANUAL 边冲突须给出 manualEdgePresent（true 保留冲突涉及边，false 全部移除）。
     */
    public record MergeResolutionInput(String conflictId, String choice,
                                       PlanTaskInput manualTask, Boolean manualEdgePresent) {
    }

    /**
     * 三方合并请求：requestId 幂等键（同参重放首次快照、异参 409、失败不占键）；
     * mergeKey 业务唯一键；left/rightExpectedVersion 为两草稿分支的修订计数器快照。
     */
    public record MergeRequest(String requestId, String mergeKey, Integer baseVersion,
                               Integer leftVersion, Integer rightVersion,
                               Integer leftExpectedVersion, Integer rightExpectedVersion,
                               List<MergeResolutionInput> resolutions) {
    }

    /** 方案任务执行（启动/完成）请求。 */
    public record PlanTaskActionRequest(String commandKey) {
    }
}
