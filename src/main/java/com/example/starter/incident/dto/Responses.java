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
     * 资源租约字段（resourceKey/authorizationVersion/assignedBy/assignedAt/expiresAt）
     * 仅已分配资源的任务有值，否则为 null；authorizationExpired 按查询时时钟计算，
     * 读取不改变任务状态；status 为按时钟判定的有效状态（租约到期且非终态时
     * 显示 AUTHORIZATION_EXPIRED）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           String resourceKey, Long authorizationVersion, String assignedBy,
                           Instant assignedAt, Instant expiresAt, boolean authorizationExpired) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 资源租约视图：租约区间 [assignedAt, expiresAt) 左闭右开，UTC；
     * expired 按查询/命令时时钟判定；taskStatus 为任务当前（有效）状态。
     */
    public record ResourceAssignmentView(String taskKey, String resourceKey,
                                         long authorizationVersion, String assignedBy,
                                         Instant assignedAt, Instant expiresAt, boolean expired,
                                         String taskStatus) {
    }

    /**
     * 资源授权证据视图：oldAuthorizationVersion 仅 ASSIGN 为 null，
     * newAuthorizationVersion 仅 EXPIRY_DETECTED/EXECUTION_BLOCKED 为 null，
     * attemptedAction 仅 EXECUTION_BLOCKED 有值；按 id 升序即发生顺序。
     */
    public record ResourceEventView(long id, String eventType, String taskKey, String resourceKey,
                                    Long oldAuthorizationVersion, Long newAuthorizationVersion,
                                    String actor, String attemptedAction, Instant occurredAt) {
    }

    /** 任务执行日志视图：note 仅 CONTINUE 必有值。 */
    public record ExecutionLogView(String action, String note, String actor, Instant occurredAt) {
    }

    /**
     * 任务资源明细与历史：current 为当前租约（未分配为 null），events 为授权证据、
     * executionLogs 为执行日志，均按发生顺序返回；只读，不隐式写入。
     */
    public record TaskResourceHistoryView(ResourceAssignmentView current,
                                          List<ResourceEventView> events,
                                          List<ExecutionLogView> executionLogs) {
    }

    /** 续期结果中单个任务的视图：taskStatus 为续期后的任务状态。 */
    public record RenewedTaskView(String taskKey, String resourceKey, long oldAuthorizationVersion,
                                  long newAuthorizationVersion, Instant newExpiresAt,
                                  String taskStatus) {
    }

    /** 批量续期响应：全部条目校验通过后才统一提交。 */
    public record RenewalView(String incidentKey, List<RenewedTaskView> renewed) {
    }

    /**
     * 资源诊断视图：now 为评估时刻（UTC）；每个租约给出当时是否到期、
     * 是否仍占用资源（任务非终态且未到期）。只读，不隐式写入。
     */
    public record ResourceDiagnosticsView(String incidentKey, Instant now,
                                          List<ResourceAssignmentView> assignments) {
    }

    /**
     * 授权到期拦截错误明细：expiresAt 为租约到期时刻，detectedAt 为按时钟判定的
     * 检测时刻，attemptedAction 为被拦截的执行动作（实际值/要求值口径）。
     */
    public record AuthorizationExpiredDetails(String taskKey, String resourceKey,
                                              long authorizationVersion, Instant expiresAt,
                                              Instant detectedAt, String attemptedAction) {
    }
}
