package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 */
public final class Responses {

    private Responses() {
    }

    /** 事件当前视图：pendingTransferTo 为待接受的交接目标人，无则 null。 */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               Instant createdAt, Instant updatedAt, Instant deadlineAt) {
    }

    /** 交接单视图。 */
    public record TransferView(long id, String fromCommander, String toCommander,
                               String status, Instant createdAt, Instant acceptedAt) {
    }

    /** 处置记录视图。 */
    public record ActionView(String actionKey, String actionType, String note,
                             Instant occurredAt, String actor, Instant createdAt) {
    }

    /** 状态流转历史视图：fromStatus 在创建记录中为 null。 */
    public record StatusChangeView(String fromStatus, String toStatus, String actor, Instant occurredAt) {
    }

    /**
     * 遏制逾期升级记录视图：deadlineAt 为接管时确定的遏制期限（UTC）；
     * triggeredAt 为触发时刻；note/acknowledgedBy/acknowledgedAt 仅 ACKNOWLEDGED 有值。
     */
    public record EscalationView(Long id, Instant deadlineAt, Instant triggeredAt,
                                 String triggeredCommander, String status, String note,
                                 String acknowledgedBy, Instant acknowledgedAt, Instant createdAt) {
    }

    /** 完整历史：事件本体 + 状态流转 + 处置记录 + 交接记录 + 升级记录。 */
    public record HistoryView(IncidentView incident, List<StatusChangeView> statusHistory,
                              List<ActionView> actions, List<TransferView> transfers,
                              List<EscalationView> escalations) {
    }

    /**
     * 升级视图：遏制期限 deadlineAt（REPORTED 为空）、当前升级 current（无则 null）
     * 及该事件完整升级历史 history（当前每事件至多一条）。
     * 检查入口与升级查询均返回该结构；只读查询不会隐式写入。
     */
    public record EscalationHistoryView(Instant deadlineAt, EscalationView current,
                                        List<EscalationView> history) {
    }

    /**
     * 任务阻塞事件视图：incidentStatus 为查询时目标事件的当前状态（不写回依赖任务）；
     * resolved 表示该事件已进入 CONTAINED/RESOLVED/CLOSED，阻塞已解除。
     */
    public record TaskBlockerView(String incidentKey, String incidentStatus, boolean resolved) {
    }

    /**
     * 处置任务视图：blockers 按阻塞事件键排序；doneBy/doneAt 仅 DONE 有值，
     * cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 方案视图：activeVersion 为当前活动 PUBLISHED 版本号。 */
    public record PlanView(String planKey, Long activeVersion, Instant createdAt) {
    }

    /**
     * 方案任务视图：completedBy/completedAt 仅 COMPLETED 有值。
     */
    public record PlanTaskView(String taskId, String incidentKey, String groupCode, String title,
                               String assignee, String status, String completedBy,
                               Instant completedAt) {
    }

    /** 方案依赖边视图：fromTaskId 为前置任务，toTaskId 为后继任务。 */
    public record PlanEdgeView(String fromTaskId, String toTaskId) {
    }

    /**
     * 方案版本视图：tasks 按 taskId、edges 按 (fromTaskId, toTaskId) 稳定排序；
     * expectedVersion 为草稿乐观锁序号。
     */
    public record PlanVersionView(long versionNo, String status, Long baseVersion,
                                  long expectedVersion, List<PlanTaskView> tasks,
                                  List<PlanEdgeView> edges) {
    }

    /**
     * 三方差异项：itemKey 形如 "task:T-1" / "edge:A-&gt;B"；kind 为 TASK/EDGE；
     * change 为 LEFT/RIGHT（单侧改动采用侧）、BOTH（双侧相同改动）、CONFLICT（显式冲突）。
     */
    public record DiffItemView(String itemKey, String kind, String change) {
    }

    /** 任务冲突视图：base/left/right 为三方内容，删除侧为 null。 */
    public record TaskConflictView(String conflictKey, String type, String taskId,
                                   PlanTaskView base, PlanTaskView left, PlanTaskView right) {
    }

    /** 边冲突视图：同一对任务上的反向新增边。 */
    public record EdgeConflictView(String conflictKey, String type, PlanEdgeView leftEdge,
                                   PlanEdgeView rightEdge) {
    }

    /**
     * 三方差异视图：items 与冲突列表均按键稳定排序；只读计算，不隐式写入。
     */
    public record DiffView(long baseVersion, long leftVersion, long rightVersion,
                           List<DiffItemView> items, List<TaskConflictView> taskConflicts,
                           List<EdgeConflictView> edgeConflicts) {
    }

    /** 冲突解决视图：MANUAL 时 manualTask/manualEdge 为冻结的手工内容。 */
    public record MergeResolutionView(String conflictKey, String choice, PlanTaskView manualTask,
                                      PlanEdgeView manualEdge) {
    }

    /**
     * 合并证据视图：冻结三方差异、全部冲突解决、最终任务集与边集；
     * 只读查询与 requestId 同参重放均返回该首次快照。
     */
    public record MergeEvidenceView(String mergeKey, String requestId, long baseVersion,
                                    long leftVersion, long rightVersion, long resultVersion,
                                    DiffView diff, List<MergeResolutionView> resolutions,
                                    List<PlanTaskView> tasks, List<PlanEdgeView> edges,
                                    String createdBy, Instant createdAt) {
    }
}
