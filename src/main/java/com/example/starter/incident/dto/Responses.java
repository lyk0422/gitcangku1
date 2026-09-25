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
     * 挂起区间明细：suspendedAt 为挂起起始 UTC 时刻；resumedAt/resumedBy/resumeNote
     * 仅已封口区间有值，生效中区间为 null；suspendedDurationMillis 为区间挂起时长（毫秒），
     * 生效中区间按查询当前时刻计算。
     */
    public record SuspensionView(Long id, String suspendKey, String reason, String suspendedBy,
                                 Instant suspendedAt, String resumeNote, String resumedBy,
                                 Instant resumedAt, long suspendedDurationMillis) {
    }

    /**
     * 挂起区间与剩余时限视图：originalDeadlineAt 为接管时确定的原始遏制期限；
     * suspended 标识当前是否处于生效中挂起；effectiveDeadlineAt 为按当前时钟与全部挂起
     * 区间实时计算的有效期限（生效中挂起随当前时刻顺延，恢复后由封口区间固定顺延量）；
     * remainingMillis 为实时剩余时限（毫秒，挂起期间冻结不递减，负值表示已超时）；
     * totalSuspendedMillis 为截至当前累计挂起时长；capMillis 为挂起上限（=原时限一倍）；
     * intervals 为按发生顺序排列的不可变挂起区间明细。
     * REPORTED 事件无遏制期限时各期限字段为 null、剩余为 0。
     */
    public record SuspensionHistoryView(Instant originalDeadlineAt, boolean suspended,
                                        Instant effectiveDeadlineAt, long remainingMillis,
                                        long totalSuspendedMillis, long capMillis,
                                        List<SuspensionView> intervals) {
    }
}
