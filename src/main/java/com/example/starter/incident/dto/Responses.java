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
     * cancelledBy/cancelledAt 仅 CANCELLED 有值；lease 为当前活动租约，无租约或租约已结束时为 null。
     * status 为按读取时钟判定的有效状态：OPEN 任务的活动租约到期时显示 AUTHORIZATION_EXPIRED
     * （只读判定，不写回）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           TaskLeaseView lease) {
    }

    /**
     * 资源租约视图：leaseVersion 初始分配为 1、每次续期 +1；assignedBy 为原分配人，
     * authorizedBy 为本版本授权人，authorizerRole 仅续期版本有值；
     * status 为按读取时钟判定的有效状态（ACTIVE 且当前时刻 ≥ expiresAt 时显示 EXPIRED）；
     * remainingSeconds 为剩余授权秒数（实际值，仅有效活动租约有值，到期为 0，其余状态为 null）。
     */
    public record TaskLeaseView(String resourceKey, String authVersion, int leaseVersion,
                                String assignedBy, String authorizedBy, String authorizerRole,
                                Instant assignedAt, Instant expiresAt, String status,
                                Long remainingSeconds) {
    }

    /**
     * 资源使用证据视图：eventType 取值 ASSIGN/RENEW/EXPIRE/RELEASE/EXECUTION_BLOCKED；
     * oldAuthVersion 在 ASSIGN 时为 null，newAuthVersion 在 EXPIRE/RELEASE/EXECUTION_BLOCKED 时为 null；
     * detail 仅 EXECUTION_BLOCKED（被拦截操作）与 RELEASE（回收原因）有值。
     */
    public record LeaseEventView(long id, String eventType, String resourceKey,
                                 String oldAuthVersion, String newAuthVersion, String actor,
                                 Instant occurredAt, String detail) {
    }

    /** 任务执行日志视图：action 取值 START/CONTINUE，occurredAt 为服务端 UTC 时刻。 */
    public record ExecutionLogView(String action, String actor, Instant occurredAt) {
    }

    /**
     * 任务资源明细与历史视图（只读，不改变状态）：currentLease 为当前活动租约（无则 null），
     * leases 为全部租约版本（按版本升序），events 为资源使用证据（按发生顺序），
     * executions 为执行日志（按发生顺序，任务到期后保留）。
     */
    public record TaskResourceHistoryView(String taskKey, TaskLeaseView currentLease,
                                          List<TaskLeaseView> leases, List<LeaseEventView> events,
                                          List<ExecutionLogView> executions) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }
}
