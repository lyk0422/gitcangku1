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
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 事件资源可用量视图：availableQuantity 为当前可征调数量（件）。 */
    public record ResourceInventoryView(String incidentKey, String resourceCode,
                                        int availableQuantity, Instant createdAt, Instant updatedAt) {
    }

    /**
     * 互助征调租约视图：固定保留首次征调的来源与目标事件；
     * quantity 征调总量、returnedQuantity 已还累计、outstandingQuantity 待还（件）；
     * exclusive 是否排他占用；status 为 ACTIVE/CLOSED；expiresAt 为 UTC 到期时刻；
     * closedAt 仅恰好还完关闭时有值。
     */
    public record AidLeaseView(String requestId, String sourceIncidentKey, String targetIncidentKey,
                               String resourceCode, int quantity, int returnedQuantity,
                               int outstandingQuantity, boolean exclusive, String status,
                               Instant expiresAt, String createdBy, Instant createdAt,
                               Instant closedAt) {
    }

    /** 分次归还流水视图：quantity 为本次实际归还数量（件），returnedAt 为 UTC 时刻。 */
    public record AidReturnView(long id, int quantity, String returnedBy, Instant returnedAt,
                                Instant createdAt) {
    }

    /**
     * 租约关联账务视图：租约本体 + 来源事件当前资源扣减账（availableQuantity 为扣减/归还后的可用量）
     * + 分次归还流水。
     */
    public record AidLeaseDetailView(AidLeaseView lease, ResourceInventoryView sourceInventory,
                                     List<AidReturnView> returns) {
    }
}
