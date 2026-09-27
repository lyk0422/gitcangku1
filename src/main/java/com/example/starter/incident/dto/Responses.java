package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

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

    /**
     * 封存包内一条明细视图：seq 从 1 起按 (occurredAt, recordKey) 升序；
     * recordType 为 COMMAND/TRANSFER/TASK_STATUS/TASK_BLOCKER_EDGE；
     * recordVersion 为该记录固化时的版本序号；payload 为原始快照 JSON（原样返回）。
     * taskId/blockerIncidentId 仅任务相关条目有值，其余为 null。
     */
    public record SealRecordView(int seq, String recordType, String recordKey, Instant occurredAt,
                                 int recordVersion, Long taskId, Long blockerIncidentId,
                                 JsonNode payload) {
    }

    /**
     * 封存更正附录视图：oldValue/newValue 允许为 null，表达原值或更正值为空。
     */
    public record SealCorrectionView(long id, int sealVersion, String recordKey, String fieldName,
                                     String oldValue, String newValue, String reason,
                                     String correctedBy, Instant createdAt) {
    }

    /**
     * 封存包视图：版本事件内递增；cutoffAt 为左闭右开截至时刻；
     * recordCount/edgeCount 为包内实际值；contentHash 为验真摘要；
     * records 为明细（列表查询时可能为空数组，详情查询时完整返回）。
     */
    public record SealView(long id, int version, Instant cutoffAt, int recordCount, int edgeCount,
                           String contentHash, String sealedBy, Instant sealedAt,
                           List<SealRecordView> records) {
    }

    /** 封存包列表视图：seals 按版本升序。 */
    public record SealListView(String incidentKey, List<SealView> seals) {
    }

    /** 封存包详情视图：含全部明细与该包更正附录（按追加顺序）。 */
    public record SealDetailView(SealView seal, List<SealCorrectionView> corrections) {
    }

    /**
     * 封存验真视图：recomputedHash 为按当前明细重算的摘要；
     * verified 为 true 表示与封存时 contentHash 一致（包内容未被改写）。
     */
    public record SealVerifyView(int version, String storedHash, String recomputedHash,
                                 boolean verified) {
    }
}
