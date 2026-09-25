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
     * cancelledBy/cancelledAt 仅 CANCELLED 有值，startedBy/startedAt 仅 IN_PROGRESS 及
     * 之后状态有值，evacuatedBy/evacuatedAt 仅 EVACUATED 有值。
     * workGrids 为规范化排序后的作业网格集合；finalPosition 为最终位置网格码，未指定为 null。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, boolean highRisk, List<String> workGrids,
                           String finalPosition, String createdBy, Instant createdAt,
                           String startedBy, Instant startedAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt,
                           String evacuatedBy, Instant evacuatedAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 疏散区域视图：zoneKey 为服务端生成的指纹键；grids 为规范化排序后的网格集合；
     * 窗口为 UTC 左闭右开；latest 表示谱系当前版本；effective 表示查询时刻处于窗口内。
     */
    public record ZoneView(String zoneKey, String groupKey, int version, List<String> grids,
                           Instant effectiveFrom, Instant effectiveTo, String riskLevel,
                           boolean latest, boolean effective, String operator, Instant createdAt) {
    }

    /** 事件疏散区域列表视图：含全部谱系版本，按登记顺序返回。 */
    public record IncidentZonesView(String incidentKey, List<ZoneView> zones) {
    }

    /**
     * 任务阻断快照视图：区域生效时对未开始命中任务固化的区域快照；
     * active 表示查询时刻仍在阻断（未解除且区域仍有效）。
     */
    public record ZoneBlockView(String taskKey, String zoneKey, int zoneVersion,
                                List<String> zoneGrids, Instant zoneEffectiveFrom,
                                Instant zoneEffectiveTo, String riskLevel,
                                Instant blockedAt, Instant releasedAt, boolean active) {
    }

    /** 事件任务阻断列表视图。 */
    public record IncidentZoneBlocksView(String incidentKey, List<ZoneBlockView> blocks) {
    }

    /**
     * 撤离豁免视图：zoneVersion 为豁免针对的区域版本；
     * valid 表示该版本仍为区域谱系当前版本（区域修订后旧版本豁免失效）。
     */
    public record ExemptionView(String taskKey, String zoneKey, int zoneVersion, String reason,
                                String grantedBy, Instant createdAt, boolean valid) {
    }

    /** 事件豁免列表视图（豁免版本查询）。 */
    public record IncidentExemptionsView(String incidentKey, List<ExemptionView> exemptions) {
    }

    /** 批量派工结果视图：tasks 为派工后的任务（IN_PROGRESS），leasedResources 为获取的资源键。 */
    public record DispatchView(String incidentKey, List<TaskView> tasks,
                               List<String> leasedResources) {
    }
}
