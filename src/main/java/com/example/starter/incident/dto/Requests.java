package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 */
public final class Requests {

    private Requests() {
    }

    /** 事件上报请求。 */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter) {
    }

    /** 接管请求。 */
    public record TakeoverRequest(String commandKey) {
    }

    /** 交接发起请求：toCommander 必须不同于当前指挥人。 */
    public record TransferRequest(String commandKey, String toCommander) {
    }

    /** 交接接受请求，操作人由 X-Actor-Id 指定且须为待接受目标人。 */
    public record TransferAcceptRequest(String commandKey) {
    }

    /** 处置记录追加请求。 */
    public record ActionRequest(String commandKey, String actionKey, String actionType,
                                String note, Instant occurredAt) {
    }

    /** 状态变更请求：targetStatus 只允许 CONTAINED / RESOLVED / CLOSED。 */
    public record StatusRequest(String commandKey, String targetStatus) {
    }

    /** 遏制逾期检查请求：以注入 Clock 的当前时刻评估，不做定时扫描。 */
    public record EscalationCheckRequest(String commandKey) {
    }

    /** 升级确认请求：note 为非空处置说明，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record EscalationAckRequest(String commandKey, String note) {
    }

    /**
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title 非空；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys) {
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /** 方案创建请求：planKey 全局唯一。 */
    public record PlanCreateRequest(String planKey) {
    }

    /** 草稿修订创建请求：baseVersion 须为当前活动 PUBLISHED 版本号。 */
    public record RevisionCreateRequest(Long baseVersion) {
    }

    /**
     * 方案任务完整字段输入：taskId 为稳定任务标识；status 取值 PENDING/IN_PROGRESS/COMPLETED；
     * completedBy/completedAt 仅 COMPLETED 有值，其余状态必须为 null。
     */
    public record PlanTaskInput(String taskId, String incidentKey, String groupCode, String title,
                                String assignee, String status, String completedBy,
                                Instant completedAt) {
    }

    /** 方案依赖边输入：fromTaskId 为前置任务，toTaskId 为后继任务。 */
    public record PlanEdgeInput(String fromTaskId, String toTaskId) {
    }

    /**
     * 草稿整体替换请求：expectedVersion 为草稿乐观锁序号，须与当前一致；
     * tasks/edges 为替换后的完整任务集与边集。
     */
    public record RevisionUpdateRequest(Long expectedVersion, List<PlanTaskInput> tasks,
                                        List<PlanEdgeInput> edges) {
    }

    /** 方案任务执行（开始/完成）请求，操作人由 X-Actor-Id 指定。 */
    public record PlanTaskActionRequest(String commandKey) {
    }

    /**
     * 一条冲突解决：conflictKey 标识冲突项；choice 取值 LEFT/RIGHT/MANUAL；
     * 任务冲突 MANUAL 须给 manualTask 完整字段；边冲突 MANUAL 须给 manualEdge
     * （manualEdge 两端点为 null 表示最终无边，manualEdge 整体缺失视为未给出边结果）。
     */
    public record MergeResolutionRequest(String conflictKey, String choice,
                                         PlanTaskInput manualTask, PlanEdgeInput manualEdge) {
    }

    /**
     * 三方合并请求：requestId 为幂等键（同参重放首次快照，冲突解决项换序等价，异参 409，
     * 失败不占键）；mergeKey 全局唯一；base/left/right 为版本号，
     * left/rightExpectedVersion 为两侧草稿乐观锁序号；resolutions 须一次覆盖全部冲突。
     */
    public record PlanMergeRequest(String requestId, String mergeKey, Long baseVersion,
                                   Long leftVersion, Long rightVersion,
                                   Long leftExpectedVersion, Long rightExpectedVersion,
                                   List<MergeResolutionRequest> resolutions) {
    }
}
