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
     * 封存包头视图：version 为事件内递增版本；sealUntil 为封存截至 UTC 时刻
     * （左闭右开，不含该时刻）；recordCount/edgeCount 为包内实际记录数与依赖边数。
     */
    public record SealView(int version, Instant sealUntil, Instant sealedAt, String sealedBy,
                           int recordCount, int edgeCount) {
    }

    /**
     * 封存记录视图：recordVersion 为固化时该记录的业务版本；seq 为包内
     * 按 (occurredAt, recordKey) 升序的序号；payload 为固化快照 JSON，不可变。
     */
    public record SealRecordView(String recordType, String recordKey, int recordVersion,
                                 Instant occurredAt, int seq, JsonNode payload) {
    }

    /** 固化依赖边视图：本事件任务 taskKey → 阻塞事件 blockerIncidentKey。 */
    public record SealEdgeView(String taskKey, String blockerIncidentKey, int seq) {
    }

    /**
     * 封存包明细视图：包头 + 全部封存记录 + 全部固化依赖边 + 该包全部更正附录。
     */
    public record SealDetailView(SealView seal, List<SealRecordView> records,
                                 List<SealEdgeView> edges, List<AppendixView> appendices) {
    }

    /** 封存历史视图：seals 按版本升序返回。 */
    public record SealHistoryView(String incidentKey, List<SealView> seals) {
    }

    /**
     * 更正附录视图：引用原包版本与原记录标识，保留原值、新值、理由与操作人；
     * oldValue 为 null 表示原字段为 null。
     */
    public record AppendixView(long id, int sealVersion, String recordKey, String fieldName,
                               String oldValue, String newValue, String reason, String operator,
                               Instant createdAt) {
    }

    /** 更正附录历史视图：appendices 按追加顺序返回。 */
    public record AppendixHistoryView(String incidentKey, List<AppendixView> appendices) {
    }

    /**
     * 封存诊断视图（只读）：汇总该事件封存包数量、最新版本、截至时刻范围、
     * 封存记录与依赖边总数、更正附录总数；无封存包时 latestVersion 与时间范围为 null。
     */
    public record SealDiagnosticsView(String incidentKey, int sealCount, Integer latestVersion,
                                      Instant earliestSealUntil, Instant latestSealUntil,
                                      long recordCount, long edgeCount, long appendixCount) {
    }
}
