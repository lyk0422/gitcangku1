package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 */
public final class Responses {

    private Responses() {
    }

    /** 事件当前视图：pendingTransferTo 为待接受的交接目标人，无则 null；version 为乐观版本号。 */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               long version,
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
     * 处置任务视图：blockers 按阻塞事件键排序；startedBy/startedAt 仅经过 IN_PROGRESS 有值，
     * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy,
                           String startedBy, Instant startedAt, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 资源视图：holderIncidentKey 为登记持有事件。 */
    public record ResourceView(String resourceKey, String holderIncidentKey,
                               String acquiredBy, Instant acquiredAt) {
    }

    /** 事件资源列表视图。 */
    public record IncidentResourcesView(String incidentKey, List<ResourceView> resources) {
    }

    /** 代理人视图。 */
    public record DelegateView(String delegate, String registeredBy, Instant createdAt) {
    }

    /** 事件代理人列表视图。 */
    public record IncidentDelegatesView(String incidentKey, List<DelegateView> delegates) {
    }

    /**
     * 交接资源项视图：settled 表示该资源已归还来源；taskKeys 为当前仍引用该资源的目标任务键。
     */
    public record HandoffItemView(String resourceKey, boolean settled, List<String> taskKeys,
                                  Instant settledAt) {
    }

    /**
     * 互助交接视图：status 为 ACTIVE/SETTLED；endReason/endTriggeredAt 仅在
     * 目标关闭或租约到期触发结束后有值；settlements 为已写入的不可变结算。
     */
    public record HandoffView(String handoffKey, String sourceIncidentKey, String targetIncidentKey,
                              String receiver, long sourceVersion, long targetVersion,
                              String operator, Instant leaseStart, Instant leaseEnd,
                              String status, String endReason, Instant endTriggeredAt,
                              List<HandoffItemView> items, List<SettlementView> settlements,
                              Instant createdAt, Instant settledAt) {
    }

    /** 事件相关交接列表视图（含作为来源与作为目标的交接）。 */
    public record IncidentHandoffsView(String incidentKey, List<HandoffView> handoffs) {
    }

    /** 不可变交接结算视图：reason 为 TARGET_CLOSED/LEASE_EXPIRED。 */
    public record SettlementView(String handoffKey, String resourceKey, String reason,
                                 String returnedToIncidentKey, Instant settledAt) {
    }

    /** 事件相关结算列表视图。 */
    public record IncidentSettlementsView(String incidentKey, List<SettlementView> settlements) {
    }

    /**
     * 资源当前责任视图：responsibleIncidentKey 为当前责任事件；
     * lentOut 为 true 表示资源经进行中交接借出，责任方为该交接目标事件。
     */
    public record ResourceResponsibilityView(String resourceKey, String holderIncidentKey,
                                             String responsibleIncidentKey, boolean lentOut,
                                             String handoffKey, Instant leaseStart, Instant leaseEnd) {
    }

    /** 关闭阻断原因视图：type 为 LENT_RESOURCE（借出未归还）或 UNFINISHED_TASK（任务未终态）。 */
    public record CloseBlockerView(String type, String resourceKey, String handoffKey,
                                   String taskKey, String message) {
    }

    /** 关闭阻断查询视图：blockers 为空表示可关闭。 */
    public record CloseBlockersView(String incidentKey, boolean closeable,
                                    List<CloseBlockerView> blockers) {
    }

    /** 租约到期结算结果视图：settlements 为本次新写入的结算记录。 */
    public record HandoffSettleView(String incidentKey, List<SettlementView> settlements) {
    }
}
