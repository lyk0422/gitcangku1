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
     * 封存包条目视图：seqNo 从 1 开始，按 occurredAt 升序、同刻按 recordType+recordKey 升序；
     * recordVersion 为封存时记录修订版本；fromTaskKey/toIncidentKey 仅 DEPENDENCY 有值；
     * payload 为封存时原样固化的记录快照 JSON（保留字段与 null 语义）。
     */
    public record SealItemView(int seqNo, String recordType, String recordKey, Instant occurredAt,
                               int recordVersion, String fromTaskKey, String toIncidentKey,
                               JsonNode payload) {
    }

    /**
     * 封存包视图：版本事件内递增；cutoffAt 为截至 UTC 时刻（左闭右开）；
     * recordCount 为包内实际条数；contentHash 为包内容摘要；items 按 seqNo 升序。
     */
    public record SealView(int version, Instant cutoffAt, Instant sealedAt, String createdBy,
                           int recordCount, String contentHash, List<SealItemView> items,
                           List<CorrectionView> corrections) {
    }

    /**
     * 事件封存包清单视图：seals 只含元数据（不含条目），按版本升序。
     */
    public record SealListView(String incidentKey, int latestVersion, List<SealSummaryView> seals) {
    }

    /** 封存包摘要视图（清单用）。 */
    public record SealSummaryView(int version, Instant cutoffAt, Instant sealedAt, String createdBy,
                                  int recordCount, String contentHash) {
    }

    /**
     * 更正附录视图：oldValue 取自原包快照，原值为 null 时为 null；newValue 为更正值。
     */
    public record CorrectionView(long id, int packageVersion, String recordType, String recordKey,
                                 String fieldName, String oldValue, String newValue, String reason,
                                 String operator, Instant createdAt) {
    }

    /**
     * 封存包诊断视图（只读）：actualItemCount 为实际重数条数，storedRecordCount 为包记录的
     * 要求值；orderingConsistent 为发生时刻+同刻标识排序与序号一致性；
     * storedHash/recomputedHash 分别为固化摘要与重算摘要；danglingEdges 为当前已无法解析到
     * 事件的依赖边标识；healthy 为各检查全部通过。
     */
    public record SealDiagnosticsView(int version, Instant cutoffAt, int actualItemCount,
                                      int storedRecordCount, boolean countMatch,
                                      boolean orderingConsistent, String storedHash,
                                      String recomputedHash, boolean hashMatch, int edgeCount,
                                      List<String> danglingEdges, int correctionsCount,
                                      boolean healthy, String message) {
    }
}
