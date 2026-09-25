package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 * 所有事件/任务结果均显式标注 domain（REAL/DRILL），演练结果另带 drillKey/drillBatch。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 事件当前视图：domain 显式标注真实/演练域；drillKey/drillBatch 仅演练域非空；
     * pendingTransferTo 为待接受的交接目标人，无则 null。
     */
    public record IncidentView(String domain, String incidentKey, String drillKey, String drillBatch,
                               String severity, String summary, String reporter, String status,
                               String commander, String pendingTransferTo,
                               Instant createdAt, Instant updatedAt) {
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
    public record EscalationView(long id, String escalateTo, String reason, String actor, Instant createdAt) {
    }

    /** 处置任务视图：blockerIncidentKeys 为同域前置阻塞事件业务键列表。 */
    public record TaskView(String domain, String taskKey, String title, String status,
                           List<String> blockerIncidentKeys, String actor,
                           Instant createdAt, Instant completedAt) {
    }

    /** 完整历史：事件本体 + 状态流转 + 处置记录 + 交接记录 + 升级记录 + 任务。 */
    public record HistoryView(IncidentView incident, List<StatusChangeView> statusHistory,
                              List<ActionView> actions, List<TransferView> transfers,
                              List<EscalationView> escalations, List<TaskView> tasks) {
    }

    /** 演练批次清理结果。 */
    public record CleanupView(String cleanupKey, String batchKey, int deletedIncidents,
                              String status, Instant createdAt) {
    }

    /** 演练清理历史条目。 */
    public record CleanupHistoryItem(String cleanupKey, String batchKey, int deletedIncidents,
                                     String actor, Instant createdAt) {
    }
}
