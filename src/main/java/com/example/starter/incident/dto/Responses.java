package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 事件当前视图：pendingTransferTo 为待接受的交接目标人，无则 null；
     * version 为乐观并发版本号（合并成功时双方各加一）；
     * mergedIntoIncidentKey 仅 MERGED 状态有值，指向存续事件键，其余为 null。
     */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               Instant createdAt, Instant updatedAt, Instant deadlineAt,
                               long version, String mergedIntoIncidentKey) {
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
     * 合并记录视图：migratedTaskKeys 为本次合并迁移到存续事件的任务键（按任务键排序）；
     * 记录不可变，查询按落库顺序稳定返回。
     */
    public record MergeRecordView(String mergeKey, String survivingIncidentKey,
                                  String mergedIncidentKey, String actor,
                                  List<String> migratedTaskKeys, Instant createdAt) {
    }

    /** 合并记录列表视图：merges 按落库顺序稳定排序。 */
    public record MergeRecordsView(List<MergeRecordView> merges) {
    }

    /** 单任务当前归属视图：ownerIncidentKey 为任务当前所属事件键（链式合并取最终存续事件）。 */
    public record TaskOwnershipView(String taskKey, String ownerIncidentKey, String status) {
    }

    /**
     * 事件任务归属查询视图：tasks 为源自该事件的全部任务当前归属（按 taskKey 稳定排序）；
     * mergedIntoIncidentKey 仅 MERGED 状态有值。
     */
    public record IncidentTaskOwnershipView(String incidentKey, String status,
                                            String mergedIntoIncidentKey,
                                            List<TaskOwnershipView> tasks) {
    }
}
