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
     * 处置任务视图：blockers 按阻塞事件键排序；requiredCredentials 为字典序必需资质集合
     * （空列表表示非高危任务）；startedBy/startedAt 仅已开始任务有值；
     * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值；
     * credentialRisk 为当前任务是否处于资质风险门禁。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<String> requiredCredentials, List<TaskBlockerView> blockers,
                           String createdBy, Instant createdAt, String startedBy, Instant startedAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           boolean credentialRisk) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN/IN_PROGRESS/CREDENTIAL_RISK 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 资源资质视图：validFrom 为空表示登记即生效。 */
    public record CredentialView(String resourceId, String credentialCode, Instant validFrom,
                                 Instant validUntil, String status, long version,
                                 String revokedBy, Instant revokedAt, String revokeReason) {
    }

    /** 资源的全部资质视图，按资质代码字典序返回。 */
    public record ResourceCredentialsView(String resourceId, List<CredentialView> credentials) {
    }

    /** 租约视图：current 表示是否为任务当前租约。 */
    public record LeaseView(long leaseId, String incidentKey, String taskKey, String resourceId,
                            long resourceVersion, Instant leaseStart, Instant leaseEnd,
                            List<String> requiredCredentials, String status, boolean current,
                            String createdBy, Instant createdAt) {
    }

    /**
     * 批量租约分配结果：created 为本次单事务创建的全部租约（与请求项顺序一致）。
     */
    public record LeaseAllocateView(List<LeaseView> created) {
    }

    /** 资质风险不可变记录视图。 */
    public record CredentialRiskView(long riskId, long leaseId, String incidentKey, String taskKey,
                                     String resourceId, String credentialCode, String reason,
                                     String triggeredBy, Instant triggeredAt, Instant createdAt) {
    }

    /** 事件风险租约列表视图。 */
    public record RiskLeasesView(String incidentKey, List<CredentialRiskView> risks) {
    }

    /**
     * 资质撤销结果：credential 为撤销后资质视图；triggeredRisks 为本次撤销新产生的
     * 不可变风险记录（已完成任务不产生记录、不改写）。
     */
    public record CredentialRevokeView(CredentialView credential,
                                       List<CredentialRiskView> triggeredRisks) {
    }

    /**
     * 任务门禁原因视图：可开始/可完成为 false 时 reasons 给出按门禁类别归类的人读原因；
     * credentialRisk 为资质风险门禁，依赖满足也不能绕过。
     */
    public record TaskGateView(String incidentKey, String taskKey, String status,
                               boolean credentialRisk, boolean canStart, boolean canComplete,
                               List<String> reasons) {
    }
}
