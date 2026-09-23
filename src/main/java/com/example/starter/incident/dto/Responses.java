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

    /**
     * 联合交接冻结的 OPEN 任务摘要：version 为任务版本号；status 为冻结时状态（OPEN）；
     * blockers 为按阻塞事件键排序后的依赖列表。
     */
    public record HandoverOpenTaskView(String taskKey, long version, String status,
                                       List<String> blockers) {
    }

    /**
     * 联合交接冻结的单事件摘要：commander/status/incidentVersion 为冻结时指挥人、状态与版本；
     * openTasks 为全部 OPEN 任务（按任务键排序）；
     * escalationVersion 为未确认（OPEN）升级的版本，无未确认升级时为 null。
     */
    public record HandoverIncidentSummaryView(String incidentKey, String commander, String status,
                                              long incidentVersion,
                                              List<HandoverOpenTaskView> openTasks,
                                              Long escalationVersion) {
    }

    /**
     * 联合交接完整冻结摘要：incidents 按事件键排序，接收人接受时须原样提交。
     */
    public record HandoverSummaryView(List<HandoverIncidentSummaryView> incidents) {
    }

    /**
     * 联合交接视图：submittedIncidentKeys 保留发起提交集合（去重后按提交顺序）；
     * closureIncidentKeys 为依赖闭包（按事件键排序，不可变）；
     * missingIncidentKeys 仅在提交集合遗漏闭包事件时返回（422 响应体携带），正常为 null；
     * summary 为冻结完整摘要；acceptedAt 仅 ACCEPTED 有值。
     */
    public record HandoverView(String handoverKey, String fromCommander, String toCommander,
                               String status, String handoverVersion,
                               List<String> submittedIncidentKeys, List<String> closureIncidentKeys,
                               List<String> missingIncidentKeys, HandoverSummaryView summary,
                               Instant acceptedAt, Instant createdAt) {
    }

    /**
     * 闭包事件行视图：inSubmitted 表示是否由发起方提交（false 为闭包自动补全）；
     * seqNo 为按事件键排序后的闭包序号。
     */
    public record HandoverClosureIncidentView(String incidentKey, boolean inSubmitted, int seqNo) {
    }

    /**
     * 接受时保存的不可变慢照视图（每事件一行），字段含义与 {@link HandoverIncidentSummaryView}
     * 一致，但取自切换指挥权的同一事务，保证快照与切换时状态一致。
     */
    public record HandoverSnapshotView(String incidentKey, String commander, String incidentStatus,
                                       long incidentVersion, List<HandoverOpenTaskView> openTasks,
                                       Long escalationVersion) {
    }

    /**
     * 联合交接闭包详情：交接单视图 + 闭包事件行 + 不可变闭包快照（PENDING 时为空列表）。
     */
    public record HandoverDetailView(HandoverView handover,
                                     List<HandoverClosureIncidentView> closureIncidents,
                                     List<HandoverSnapshotView> snapshots) {
    }

    /** 联合交接历史项：交接单视图 + 闭包事件行。 */
    public record HandoverHistoryItemView(HandoverView handover,
                                          List<HandoverClosureIncidentView> closureIncidents) {
    }

    /** 联合交接历史视图：按发起顺序倒序。 */
    public record HandoverHistoryView(String commander, List<HandoverHistoryItemView> handovers) {
    }
}
