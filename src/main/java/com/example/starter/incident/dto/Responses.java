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
     * 处置任务视图：blockers 按阻塞事件键排序；startedBy/startedAt 仅已开始（含终态）有值；
     * assignedResourceKey/assignedHandoffKey 为任务占用的互助借用资源及交接，解绑归还后为空；
     * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String startedBy, Instant startedAt,
                           String assignedResourceKey, String assignedHandoffKey,
                           String doneBy, Instant doneAt, String cancelledBy,
                           Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 互助资源视图：ownerIncidentKey 为来源事件键；status AVAILABLE/LEASED_OUT。 */
    public record ResourceView(String resourceKey, String ownerIncidentKey, String label,
                               String registeredBy, String status,
                               Instant createdAt, Instant updatedAt) {
    }

    /**
     * 资源当前责任视图：resource 为资源本体；responsibleParty 为当前责任方
     * （SOURCE 来源自持 / TARGET 目标事件借用中）；responsibleIncidentKey 为当前责任事件；
     * activeHandoffKey 为生效交接键（自持时为空）；currentLeaseEnd 为当前租约结束时刻。
     */
    public record ResourceResponsibilityView(ResourceView resource, String responsibleParty,
                                             String responsibleIncidentKey,
                                             String activeHandoffKey, Instant leaseStart,
                                             Instant leaseEnd, String operator) {
    }

    /** 交接视图：含两事件版本、UTC 租约、操作者/接收人、状态与结算时刻。 */
    public record HandoffView(String handoffKey, String resourceKey, String sourceIncidentKey,
                              String targetIncidentKey, long sourceVersion, long targetVersion,
                              Instant leaseStart, Instant leaseEnd, String operator,
                              String receiver, String status, Instant settledAt,
                              Instant createdAt) {
    }

    /** 不可变交接结算视图。 */
    public record HandoffSettlementView(String handoffKey, String reason,
                                        String returnedResourceKey, String detail,
                                        Instant settledAt, Instant createdAt) {
    }

    /** 批量交接结果：本批次新创建的交接视图列表。 */
    public record HandoffBatchView(String sourceIncidentKey, String targetIncidentKey,
                                   List<HandoffView> handoffs) {
    }

    /** 租约到期结算检查结果：本次新结算的不可变结算视图列表（幂等重放返回首次结果）。 */
    public record HandoffSettlementListHolder(List<HandoffSettlementView> views) {
    }

    /** 接收代理人视图。 */
    public record DelegateView(String incidentKey, String delegate, String registeredBy,
                               Instant createdAt) {
    }

    /**
     * 关闭阻断原因视图：incidentKey/status 为事件当前状态；blocked=true 时 reasons
     * 列出可区分的阻断原因（借出未归还资源及占用任务等），resources 为相关资源键。
     */
    public record CloseBlockerView(String incidentKey, String status, boolean blocked,
                                   List<String> reasons, List<String> resourceKeys) {
    }
}
