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
     * 处置任务视图：blockers 按阻塞事件键排序；version 为任务乐观版本；
     * startedAt 仅 STARTED/DONE 有值，doneBy/doneAt 仅 DONE 有值，
     * cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           long version, List<TaskBlockerView> blockers, String createdBy,
                           Instant createdAt, Instant startedAt, String doneBy, Instant doneAt,
                           String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN/STARTED 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 共享资源视图：usedCapacity 为查询时全部 ACTIVE 租约占用之和，availableCapacity 为剩余容量。 */
    public record ResourceView(String resourceKey, String name, int capacity,
                               int usedCapacity, int availableCapacity, Instant createdAt) {
    }

    /**
     * 租约视图：status 为 ACTIVE/RELEASED/REVOKED；incidentKey/taskKey 标识持有任务；
     * grantedAt 仅 ACTIVE 起有值，releasedAt 仅 RELEASED 有值，
     * revokedAt/revokeReason 仅 REVOKED 有值；requestId 为关联抢占请求标识，可空。
     */
    public record LeaseView(String leaseKey, String resourceKey, String incidentKey, String taskKey,
                            int quantity, String status, long version, Instant grantedAt,
                            Instant releasedAt, Instant revokedAt, String revokeReason,
                            String requestId, Instant createdAt) {
    }

    /** 抢占计划中的受害租约条目：leaseKey + 提交时租约版本。 */
    public record PreemptionVictim(String leaseKey, long version) {
    }

    /**
     * 抢占结果视图：revoked 为本次实际撤销的受害租约（按计划顺序），
     * granted 为新授予的租约；任一校验失败整单 409 且无任何变更。
     */
    public record PreemptionView(String requestId, List<LeaseView> revoked, LeaseView granted) {
    }

    /** 抢占闭包查询结果：victims 为必须同时抢占的全部 ACTIVE 受害租约（含传递闭包）。 */
    public record PreemptionClosureView(String resourceKey, List<LeaseView> victims) {
    }

    /** 资源占用明细视图：资源本体 + 当前全部 ACTIVE 租约。 */
    public record ResourceUsageView(ResourceView resource, List<LeaseView> activeLeases) {
    }
}
