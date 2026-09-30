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
     * credential 为当前生效派发的凭据依据（从未派发为 null）；
     * blockReason 仅 BLOCKED 有值（CREDENTIAL_EXPIRED/CREDENTIAL_REVOKED）；
     * dispatches 为历次派发记录（含首次派发与再派发），按派发次序返回。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           TaskCredentialView credential, String blockReason, Instant blockedAt,
                           List<TaskDispatchView> dispatches) {
    }

    /**
     * 任务当前凭据依据视图：取自最新一次派发记录的不可变快照；
     * expiresAt 为该凭据版本的 UTC 失效时刻；dispatchStatus 为该次派发的当前状态
     * （DISPATCHED/EXECUTING/BLOCKED）。
     */
    public record TaskCredentialView(String resourceKey, String credentialVersion,
                                     String credentialType, String region, Instant expiresAt,
                                     String dispatchStatus, Instant dispatchedAt, Instant startedAt) {
    }

    /**
     * 单次派发记录视图：区域与凭据版本为派发当时快照，历史不可变；
     * blockReason/blockedAt 仅 BLOCKED 有值；startedAt 仅 EXECUTING 有值。时间均为 UTC。
     */
    public record TaskDispatchView(int seq, String requestId, String resourceKey,
                                   String credentialVersion, String credentialType, String region,
                                   Instant expiresAt, String status, String blockReason,
                                   Instant blockedAt, Instant dispatchedAt, String dispatchedBy,
                                   Instant startedAt) {
    }

    /**
     * 资源凭据视图：status 为 ACTIVE/REVOKED；revokedAt 仅 REVOKED 有值；
     * expiresAt 为 UTC 失效时刻。
     */
    public record CredentialView(String resourceKey, String version, String credentialType,
                                 String region, String status, Instant expiresAt, Instant revokedAt,
                                 Instant createdAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }
}
