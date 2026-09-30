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
     * 任务派发记录视图：冻结派发当时的凭据版本快照（credentialVersion/type/region/expiresAt），
     * 历史执行依据不随后续凭据更新而改变。status 为 DISPATCHED/BLOCKED/SUPERSEDED；
     * blockReason 仅 BLOCKED 有值（CREDENTIAL_EXPIRED/CREDENTIAL_REVOKED）；
     * blockedAt 仅 BLOCKED 有值，supersededAt 仅 SUPERSEDED 有值。
     */
    public record DispatchView(long id, String requestId, String resourceKey, int credentialVersion,
                               String credentialType, String region, Instant credentialExpiresAt,
                               String status, String blockReason, String dispatchedBy,
                               Instant dispatchedAt, Instant blockedAt, Instant supersededAt) {
    }

    /**
     * 资源凭据视图：version 为资源内递增版本号；status 为 ACTIVE/REVOKED；
     * expiresAt 为 UTC 失效时刻（当前时刻 ≥ expiresAt 即视为过期）；revokedAt 仅 REVOKED 有值。
     */
    public record CredentialView(String resourceKey, int version, String credentialType, String region,
                                 Instant expiresAt, String status, String createdBy,
                                 Instant revokedAt, Instant createdAt) {
    }

    /**
     * 处置任务视图：blockers 按阻塞事件键排序；
     * credentialVersion 为最近一次成功派发绑定的凭据版本，未派发为 null，BLOCKED 时保留阻断所依据版本；
     * blockReason 仅 BLOCKED 有值（CREDENTIAL_EXPIRED/CREDENTIAL_REVOKED）；
     * executingBy/executingAt 进入 EXECUTING 后有值；doneBy/doneAt 仅 DONE 有值，
     * cancelledBy/cancelledAt 仅 CANCELLED 有值；dispatches 为按派发顺序返回的全部派发记录。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           String resourceKey, String region, Integer credentialVersion,
                           String blockReason, List<TaskBlockerView> blockers,
                           String createdBy, String executingBy, Instant executingAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           List<DispatchView> dispatches) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }
}
