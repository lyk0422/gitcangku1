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
     * 疏散阻断信息视图：任务处于 EVACUATION_BLOCKED 时随任务返回，固化登记时刻的区域快照。
     * effectiveFrom/effectiveTo 为 UTC 左闭右开窗口；grids 为规范化网格集合。
     */
    public record EvacuationBlockerView(String zoneKey, int version, String riskLevel,
                                        List<String> grids, Instant effectiveFrom,
                                        Instant effectiveTo) {
    }

    /**
     * 处置任务视图：workGrid 为作业网格；blockers 按阻塞事件键排序；
     * blocked 仅 EVACUATION_BLOCKED 状态非空（区域快照）；
     * doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String workGrid,
                           String status, List<TaskBlockerView> blockers,
                           EvacuationBlockerView blocked, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有未终结任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 疏散区域视图：effective 为查询时刻按窗口与状态计算的是否有效（只读计算，不隐式写入）；
     * endedAt 仅 ENDED 有值。
     */
    public record ZoneView(String zoneKey, int version, String riskLevel, List<String> grids,
                           Instant effectiveFrom, Instant effectiveTo, String status,
                           boolean effective, String registeredBy, Instant endedAt,
                           Instant createdAt) {
    }

    /** 事件的疏散区域列表视图：zones 按版本返回。 */
    public record ZoneListView(String incidentKey, List<ZoneView> zones) {
    }

    /**
     * 撤离豁免视图：version 为绑定的区域版本；zoneKey 为所属区域；taskKey 为被豁免任务。
     */
    public record ExemptionView(long id, String zoneKey, int version, String taskKey,
                                String grantedBy, Instant createdAt) {
    }

    /** 事件的豁免列表视图：exemptions 按授予顺序返回。 */
    public record ExemptionListView(String incidentKey, List<ExemptionView> exemptions) {
    }

    /**
     * 批量派工结果视图：dispatched 为本次成功派工的任务键；tasks 返回派工后各任务明细。
     */
    public record BatchDispatchView(String incidentKey, List<String> dispatched, List<TaskView> tasks) {
    }

    /**
     * 任务阻断查询视图：逐条任务返回其当前疏散命中与豁免情况。
     * blockedBy 为当前有效且命中作业网格、但任务缺少对应版本豁免的区域键（无则空列表）；
     * exemptions 为该任务已持有的豁免（区域键 + 版本）。
     */
    public record TaskBlockStatusView(String taskKey, String workGrid, String status,
                                      List<String> blockedBy, List<ExemptionView> exemptions) {
    }

    /** 事件的任务阻断查询视图：tasks 按创建顺序返回。 */
    public record TaskBlockStatusListView(String incidentKey, List<TaskBlockStatusView> tasks) {
    }
}
