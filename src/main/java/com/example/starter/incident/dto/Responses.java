package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 * 所有事件视图均带 domain 标注（REAL 真实 / DRILL 演练沙盘），演练查询结果必须显式标注域。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 事件当前视图：domain 为隔离域标注；pendingTransferTo 为待接受交接目标人，无则 null；
     * drillBatchKey 仅演练事件有值。
     */
    public record IncidentView(String incidentKey, String domain, String drillBatchKey, String severity,
                               String summary, String reporter, String status, String commander,
                               String pendingTransferTo, Instant createdAt, Instant updatedAt) {
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

    /** 升级记录视图。 */
    public record EscalationView(String fromSeverity, String toSeverity, String reason,
                                 String actor, Instant createdAt) {
    }

    /** 处置任务依赖（阻塞）边视图：两端均为同域事件。 */
    public record DependencyView(String incidentKey, String blockedByIncidentKey, Instant createdAt) {
    }

    /** 完整历史：事件本体 + 状态流转 + 处置记录 + 交接记录 + 升级记录 + 依赖边。 */
    public record HistoryView(IncidentView incident, List<StatusChangeView> statusHistory,
                              List<ActionView> actions, List<TransferView> transfers,
                              List<EscalationView> escalations, List<DependencyView> dependencies) {
    }

    /** 演练批次清理结果视图。 */
    public record CleanupView(String batchKey, String cleanupKey, int deletedIncidentCount,
                              Instant cleanedAt) {
    }

    /**
     * 演练批次视图：用于按批次的演练事件清单与清理历史。
     * cleaned 表示是否已清理；incidentCount 为当前在册（未清理）事件数，
     * deletedIncidentCount 为清理时删除的事件数（墓碑信息）。
     */
    public record DrillBatchView(String batchKey, boolean cleaned, int incidentCount,
                                 int deletedIncidentCount, String cleanupKey,
                                 Instant createdAt, Instant cleanedAt,
                                 List<IncidentView> incidents) {
    }
}
