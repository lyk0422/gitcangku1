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

    /**
     * 封存包摘要视图：覆盖区间 [lowerBoundAt, cutoffAt) 左闭右开；
     * entryCount/edgeCount 为包内实际条目数与依赖边数。
     */
    public record SealPackageView(int version, Instant lowerBoundAt, Instant cutoffAt,
                                  int entryCount, int edgeCount, String sealedBy,
                                  Instant createdAt) {
    }

    /**
     * 封存包记录条目视图：seqNo 为包内序号（按发生时刻、同刻按记录标识排序）；
     * payload 为封存时刻固化的完整字段快照（JSON 对象）。
     */
    public record SealEntryView(int seqNo, String recordType, String recordKey,
                                Instant occurredAt, int recordVersion, Object payload) {
    }

    /** 封存包任务依赖边视图：taskKey（本事件任务）→ blockerIncidentKey。 */
    public record SealEdgeView(String taskKey, String blockerIncidentKey, Instant edgeCreatedAt) {
    }

    /** 封存包明细视图：摘要 + 全部条目 + 全部依赖边。只读，不隐式写入。 */
    public record SealPackageDetailView(int version, Instant lowerBoundAt, Instant cutoffAt,
                                        int entryCount, int edgeCount, String sealedBy,
                                        Instant createdAt, List<SealEntryView> entries,
                                        List<SealEdgeView> edges) {
    }

    /**
     * 更正附录视图：oldValue 为原包中该记录的封存快照（JSON 对象），
     * newValue 为更正后内容；原包内容不被修改。
     */
    public record SealAppendixView(long id, int packageVersion, String recordKey, Object oldValue,
                                   String newValue, String reason, String actor,
                                   Instant createdAt) {
    }

    /** 封存历史视图：版本递增的包摘要列表 + 全部更正附录。只读，不隐式写入。 */
    public record SealHistoryView(String incidentKey, List<SealPackageView> packages,
                                  List<SealAppendixView> appendices) {
    }

    /**
     * 封存诊断视图：当前可封存窗口 [nextLowerBoundAt, currentTime) 内各类型待封存记录数、
     * 与当前时刻同刻的指令标识列表（非空时以当前时刻封存将返回 409）。
     * lastVersion/lastCutoffAt 无封存包时为 null。只读，不隐式写入。
     */
    public record SealDiagnosticsView(String incidentKey, String incidentStatus,
                                      Instant incidentCreatedAt, Instant currentTime,
                                      Integer lastVersion, Instant lastCutoffAt,
                                      Instant nextLowerBoundAt, long pendingActionCount,
                                      long pendingTransferCount, long pendingStatusChangeCount,
                                      long taskCount, List<String> boundaryConflictActionKeys) {
    }
}
