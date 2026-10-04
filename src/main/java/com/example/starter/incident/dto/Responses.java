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

    /** 简报内冻结的未完成任务项：任务编号、分组与冻结时状态。 */
    public record BriefingTaskItem(String taskKey, String groupCode, String status) {
    }

    /** 简报内冻结的未确认升级记录项：记录 id、状态、触发时刻与遏制期限（UTC）。 */
    public record BriefingEscalationItem(long id, String status, Instant triggeredAt,
                                         Instant deadlineAt) {
    }

    /**
     * 结构化简报内容：冻结事件状态、未完成任务编号及状态、未确认升级记录与
     * 交出人补充说明（note 为空表示无补充）。摘要 digest 由本结构的规范化 JSON
     * 计算，字段边界由 JSON 结构保证，不可碰撞。
     */
    public record BriefingContent(String incidentKey, String incidentStatus,
                                  String fromCommander, String toCommander, String note,
                                  List<BriefingTaskItem> unfinishedTasks,
                                  List<BriefingEscalationItem> unacknowledgedEscalations) {
    }

    /**
     * 简报视图：version 在同一交接单内从 1 递增；digest 为内容摘要；
     * status 为 ACTIVE/FROZEN；readbackBy/readbackAt 为接班人回读确认信息，未回读为空。
     */
    public record BriefingView(long id, long transferId, int version, String status,
                               String digest, BriefingContent content, String createdBy,
                               Instant createdAt, String readbackBy, Instant readbackAt) {
    }

    /** 回读确认视图：确认绑定具体简报版本，刷新后旧确认不能复用。 */
    public record ReadbackView(long briefingId, int version, String reader, String digest,
                               Instant confirmedAt) {
    }
}
