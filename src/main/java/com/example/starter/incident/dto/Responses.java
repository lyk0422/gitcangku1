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
     * cancelledBy/cancelledAt 仅 CANCELLED 有值。status 为按当前时钟计算的有效状态
     * （OPEN 且租约已到期时显示 AUTHORIZATION_EXPIRED，读取不写回）。
     * resourceKey/authorizationVersion/authorizationExpiresAt 为当前 ACTIVE 租约信息，无则 null。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           String resourceKey, Integer authorizationVersion,
                           Instant authorizationExpiresAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 资源租约视图：expired 为按查询时刻计算的派生状态（ACTIVE 且当前时刻 >= expiresAt）；
     * remainingSeconds 为到期剩余秒数（实际值减要求值口径的差额，已到期为负）；
     * releasedBy/releasedAt 仅 RELEASED 有值。
     */
    public record ResourceAssignmentView(long id, String taskKey, String resourceKey,
                                         int authorizationVersion, String assignedBy,
                                         Instant assignedAt, Instant expiresAt, String status,
                                         boolean expired, long remainingSeconds,
                                         String releasedBy, Instant releasedAt) {
    }

    /**
     * 租约证据视图：oldVersion 在 ASSIGN 时为 null；
     * newVersion 在 EXPIRE/EXECUTE_DENIED/RELEASE 时为 null。
     */
    public record ResourceEventView(long id, String eventType, String resourceKey,
                                    Integer oldVersion, Integer newVersion, String actor,
                                    Instant occurredAt, String detail) {
    }

    /** 续期结果视图：renewed 为本次续期的租约；validatedClosureAssignments 为闭包内仅校验未修改的租约数。 */
    public record ResourceRenewView(String incidentKey, List<ResourceAssignmentView> renewed,
                                    int validatedClosureAssignments) {
    }

    /**
     * 续期冲突明细：reason 为机器可区分原因（TASK_TERMINAL 终态 / PENDING_TRANSFER 交接中 /
     * NOT_ASSIGNER_ROLE 续期人与原分配人相同 / VERSION_NOT_INCREASING 版本未递增 /
     * EXPIRES_NOT_FUTURE 到期时刻不晚于当前 / RESOURCE_OCCUPIED 资源被其他事件占用 /
     * NO_ACTIVE_ASSIGNMENT 无生效租约）。
     */
    public record RenewConflictView(String taskKey, String resourceKey, String reason,
                                    String message) {
    }

    /** 到期时刻要求明细：实际请求值、要求下界与差额秒数（requested 距离 requiredAfter 还差多少秒）。 */
    public record ExpiryRequirementView(Instant requestedExpiresAt, Instant requiredAfter,
                                        long shortfallSeconds) {
    }

    /** 授权版本要求明细：当前版本与请求版本（要求请求版本严格大于当前版本）。 */
    public record VersionRequirementView(int currentVersion, int requestedVersion) {
    }

    /** 任务执行日志视图。 */
    public record ExecutionLogView(long id, String entryType, String note, String actor,
                                   Instant occurredAt) {
    }
}
