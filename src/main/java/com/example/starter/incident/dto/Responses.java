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
     * containmentDeadline 为遏制期限（UTC），首次接管后不变，交接不重置；REPORTED 状态为 null。
     */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               Instant containmentDeadline,
                               Instant createdAt, Instant updatedAt) {
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
     * 升级记录视图：deadline 为接管时确定的遏制期限（UTC）；triggeredAt 为触发时刻（UTC）；
     * commander 为触发当时的指挥人；dispositionNote/acknowledgedBy/acknowledgedAt
     * 仅 ACKNOWLEDGED 状态有值，其余为 null。
     */
    public record EscalationView(long id, String status, Instant deadline, Instant triggeredAt,
                                 String commander, String dispositionNote, String acknowledgedBy,
                                 Instant acknowledgedAt, Instant createdAt) {
    }

    /**
     * 单事件检查结果：created 表示本次检查是否新追加了 OPEN 记录（同键重放返回首次结果）；
     * deadline 为事件遏制期限（REPORTED 状态为 null）；escalation 为当前升级记录，无则 null。
     */
    public record EscalationCheckView(boolean created, Instant deadline, EscalationView escalation) {
    }

    /**
     * 升级查询结果：deadline 为遏制期限；current 为当前升级记录（无则 null）；
     * history 为完整升级历史（当前每事件至多一条，按落库顺序）。只读接口，不隐式写入。
     */
    public record EscalationHistoryView(Instant deadline, EscalationView current,
                                        List<EscalationView> history) {
    }

    /** 完整历史：事件本体 + 状态流转 + 处置记录 + 交接记录 + 升级记录。 */
    public record HistoryView(IncidentView incident, List<StatusChangeView> statusHistory,
                              List<ActionView> actions, List<TransferView> transfers,
                              List<EscalationView> escalations) {
    }
}
