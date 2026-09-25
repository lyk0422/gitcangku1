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
     * 挂起区间视图：resumedBy/resumeNote/resumedAt 仅已恢复（封口）有值，
     * 为空表示挂起仍生效；起始半区字段落库后不可改写。
     */
    public record SuspensionView(long id, String suspendKey, String reason, String suspendedBy,
                                 Instant suspendedAt, String resumedBy, String resumeNote,
                                 Instant resumedAt) {
    }

    /**
     * 挂起状态与剩余时限视图：deadlineAt 为接管时确定的原始遏制期限（REPORTED 为空）；
     * effectiveDeadlineAt 为排除挂起区间后的实际期限；remainingSeconds 为按当前时钟实时
     * 计算的剩余秒数（无期限时为 null，已逾期为 0，不持久化）；suspendedTotalSeconds 为
     * 累计挂起秒数（含生效中区间截至当前时刻）；suspended 表示当前是否有生效挂起；
     * overdue 表示按当前时钟与挂起区间判定是否已逾期；suspensions 为全部区间明细。
     */
    public record SuspensionStatusView(Instant deadlineAt, Instant effectiveDeadlineAt,
                                       Long remainingSeconds, long suspendedTotalSeconds,
                                       boolean suspended, boolean overdue,
                                       List<SuspensionView> suspensions) {
    }
}
