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
     * 处置任务视图：blockers 按阻塞事件键排序；startedBy/startedAt 仅 STARTED 及经
     * STARTED 的终态有值，doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅
     * CANCELLED 有值；version 为任务乐观版本（创建为 1，启动/完成/取消各加 1）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String startedBy, Instant startedAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           long version) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 租约视图：status 为 ACTIVE/RELEASED/REVOKED；version 为租约乐观版本；
     * releasedAt 仅 RELEASED 有值，revokedAt 仅 REVOKED 有值。
     */
    public record LeaseView(String leaseKey, String resourceKey, String incidentKey,
                            String taskKey, int units, String status, long version,
                            String createdBy, Instant createdAt, Instant releasedAt,
                            Instant revokedAt) {
    }

    /**
     * 资源占用视图：activeUnits 为 ACTIVE 租约单位合计，
     * availableUnits = capacity - activeUnits；activeLeases 按创建顺序返回。
     */
    public record ResourceView(String resourceKey, int capacity, int activeUnits,
                               int availableUnits, List<LeaseView> activeLeases) {
    }

    /** 资源租约历史视图：leases 含全部状态，按创建顺序返回。 */
    public record LeaseHistoryView(String resourceKey, List<LeaseView> leases) {
    }

    /** 抢占结果视图：grantedLease 为新授予租约，revokedVictims 为被撤销的受害租约。 */
    public record PreemptionView(LeaseView grantedLease, List<LeaseView> revokedVictims) {
    }

    /** 任务引用视图（抢占闭包中的 STARTED 任务等）。 */
    public record TaskRefView(String incidentKey, String taskKey) {
    }

    /**
     * 抢占闭包视图（只读）：listedVictims 为查询指定的受害租约；
     * requiredAdditionalLeases 为反向依赖闭包要求但未列出的 ACTIVE 租约；
     * startedTasks 为闭包中已 STARTED 的任务（存在时不得抢占）。
     */
    public record PreemptionClosureView(String resourceKey, List<LeaseView> listedVictims,
                                        List<LeaseView> requiredAdditionalLeases,
                                        List<TaskRefView> startedTasks) {
    }
}
