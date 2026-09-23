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
     * 冻结摘要中的单个 OPEN 任务：version 为任务 updatedAt（UTC 版本），
     * blockerKeys 为排序后的阻塞事件键（未完成阻塞关系）。
     */
    public record HandoverTaskSummary(String taskKey, String status, Instant version,
                                      List<String> blockerKeys) {
    }

    /**
     * 冻结摘要中的单个事件：commander/status 为冻结时值，version 为事件 updatedAt；
     * openTasks 含全部 OPEN 任务的版本、状态及排序后依赖；
     * unacknowledgedEscalationVersion 为未确认（OPEN）升级的 updatedAt 版本，无则 null。
     */
    public record HandoverIncidentSummary(String incidentKey, String commander, String status,
                                          Instant version, List<HandoverTaskSummary> openTasks,
                                          Instant unacknowledgedEscalationVersion) {
    }

    /**
     * 联合交接完整冻结摘要：closureIncidentKeys 为排序后的闭包事件键，
     * incidents 按事件键排序，接收人接受时须原样回传。
     */
    public record HandoverSummary(String fromCommander, String toCommander,
                                  List<String> closureIncidentKeys, List<HandoverIncidentSummary> incidents) {
    }

    /** 联合交接视图：摘要随预览返回，接受时原样回传。 */
    public record HandoverView(String handoverKey, String fromCommander, String toCommander,
                               String status, String handoverVersion, List<String> closureIncidentKeys,
                               HandoverSummary summary, Instant createdAt, Instant acceptedAt) {
    }

    /** 不可变闭包快照中的事件行。 */
    public record SnapshotIncidentView(String incidentKey, String commander, String status,
                                       Instant versionAt) {
    }

    /** 不可变闭包快照中的 OPEN 任务行（含排序后依赖）。 */
    public record SnapshotTaskView(String incidentKey, String taskKey, String status,
                                   Instant versionAt, List<String> blockerKeys) {
    }

    /** 不可变闭包快照中的未确认升级行。 */
    public record SnapshotEscalationView(String incidentKey, long escalationId, Instant versionAt) {
    }

    /** 接受成功后保存的不可变闭包快照，对应切换时一致状态。 */
    public record HandoverSnapshotView(String handoverKey, String fromCommander, String toCommander,
                                       Instant acceptedAt, List<SnapshotIncidentView> incidents,
                                       List<SnapshotTaskView> tasks,
                                       List<SnapshotEscalationView> escalations) {
    }
}
