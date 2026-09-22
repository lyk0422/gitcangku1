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
     * 阻塞目标事件视图：status 为查询时刻目标事件当前状态；
     * lifted 表示阻塞是否已解除（目标事件处于 CONTAINED/RESOLVED/CLOSED）。
     * 目标事件解除阻塞不写回依赖任务，每次查询按当前状态实时计算。
     */
    public record BlockedIncidentView(String incidentKey, String status, boolean lifted) {
    }

    /**
     * 处置任务视图：status 为 OPEN/DONE/CANCELLED；completedAt 仅 DONE 有值，cancelledAt 仅 CANCELLED 有值；
     * blockedIncidents 为 0～5 个跨事件阻塞目标的实时阻塞状态；
     * completable 表示当前是否所有阻塞事件均已解除（OPEN 任务完成校验据此进行）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           String createdBy, Instant createdAt, Instant updatedAt,
                           Instant completedAt, Instant cancelledAt,
                           List<BlockedIncidentView> blockedIncidents, boolean completable) {
    }

    /** 按 groupCode 分组的任务列表。 */
    public record TaskGroupView(String groupCode, List<TaskView> tasks) {
    }
}
