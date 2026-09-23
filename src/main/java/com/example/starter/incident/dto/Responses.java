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
     * 联合交接 OPEN 任务摘要：taskKey、版本（完成/取消时递增）、状态及
     * 排序后依赖（阻塞事件键升序）。
     */
    public record HandoverTaskSummary(String taskKey, int version, String status,
                                      List<String> dependencies) {
    }

    /** 联合交接未确认（OPEN）升级摘要：升级记录 id 与版本（确认/取消时递增）。 */
    public record HandoverEscalationSummary(long escalationId, int version) {
    }

    /**
     * 联合交接单事件摘要：冻结当前指挥人、状态、全部 OPEN 任务摘要与未确认升级摘要。
     */
    public record HandoverIncidentSummary(String incidentKey, String commander, String status,
                                          List<HandoverTaskSummary> openTasks,
                                          List<HandoverEscalationSummary> unacknowledgedEscalations) {
    }

    /** 联合交接完整摘要：闭包事件按事件键升序；接受时需原样提交。 */
    public record HandoverSummary(List<HandoverIncidentSummary> incidents) {
    }

    /**
     * 联合交接单视图：incidentKeys 为闭包事件键升序列表；
     * acceptedAt 仅 ACCEPTED 有值。
     */
    public record HandoverView(long id, String handoverKey, String fromCommander,
                               String toCommander, String status, int incidentCount,
                               List<String> incidentKeys, Instant createdAt, Instant acceptedAt) {
    }

    /**
     * 联合交接详情视图：handoverVersion 为摘要的 SHA-256 摘要。
     * PENDING 时为当前状态实时重算的预览；ACCEPTED 时为接受时保存的不可变快照。
     */
    public record HandoverDetailView(HandoverView handover, HandoverSummary summary,
                                     String handoverVersion) {
    }

    /** 事件维度的联合交接历史视图：handovers 按发起顺序返回。 */
    public record HandoverHistoryView(String incidentKey, List<HandoverView> handovers) {
    }
}
