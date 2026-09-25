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
     * requiredCredentials 为高危任务必需资质集合（按代码排序，非高危为空列表）；
     * plannedCompleteAt 为计划完成 UTC 时刻（非高危为 null）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, List<String> requiredCredentials,
                           Instant plannedCompleteAt, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 共享资源视图：version 为当前资源版本（每次资质登记或撤销递增）。 */
    public record ResourceView(String resourceKey, int version, String createdBy,
                               Instant createdAt, Instant updatedAt) {
    }

    /**
     * 资源资质视图：有效期为 UTC 半开区间 [validFrom, validUntil)；
     * revokedAt 仅已提前撤销有值。
     */
    public record CredentialView(String credentialCode, Instant validFrom, Instant validUntil,
                                 boolean revoked, Instant revokedAt, Instant createdAt) {
    }

    /** 资源资质查询视图：资源当前版本 + 全部资质（按代码排序）。 */
    public record ResourceCredentialsView(String resourceKey, int version,
                                          List<CredentialView> credentials) {
    }

    /**
     * 资源租约视图：credentialCodes 为租约覆盖的资质集合快照（按代码排序）；
     * 时段为 UTC 半开区间 [leaseStart, leaseEnd)；replacedBy 仅 REPLACED 有值。
     */
    public record LeaseView(String leaseKey, String resourceKey, int resourceVersion,
                            String incidentKey, String taskKey, List<String> credentialCodes,
                            Instant leaseStart, Instant leaseEnd, String status,
                            String replacedBy, String operator, Instant createdAt) {
    }

    /** 批量租约分配响应：leases 按规范化任务集合顺序返回。 */
    public record LeaseBatchView(String leaseKey, String resourceKey, List<LeaseView> leases) {
    }

    /** 资质风险不可变记录视图。 */
    public record RiskRecordView(long leaseId, String resourceKey, String incidentKey,
                                 String taskKey, String credentialCode,
                                 Instant revokedAt, Instant detectedAt) {
    }

    /** 风险租约视图：租约 + 关联的不可变风险记录。 */
    public record RiskLeaseView(LeaseView lease, List<RiskRecordView> riskRecords) {
    }

    /** 风险租约查询视图。 */
    public record RiskLeaseListView(List<RiskLeaseView> riskLeases) {
    }

    /**
     * 任务门禁查询视图：canStart/canComplete 为查询时刻的准入结果；
     * gateReasons 为阻止开始/完成的原因列表（空表示无门禁阻止）；
     * unresolvedBlockers 为未解除阻塞的事件键；riskCredentials 为导致
     * CREDENTIAL_RISK 的被撤销资质代码。
     */
    public record TaskGateView(String incidentKey, String taskKey, String status,
                               boolean canStart, boolean canComplete, List<String> gateReasons,
                               List<String> unresolvedBlockers, List<String> riskCredentials) {
    }

    /**
     * 资质校验失败明细（422）：issue 为 MISSING（未登记或已撤销）或
     * NOT_COVERING（有效期未严格覆盖任务计划完成时刻）。
     */
    public record CredentialIssueView(String incidentKey, String taskKey, String credentialCode,
                                      String issue, Instant validFrom, Instant validUntil) {
    }
}
