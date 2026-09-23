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

    /** 依赖图边视图：fromIncidentKey 依赖 toIncidentKey（被其阻塞）。 */
    public record GraphEdgeView(String fromIncidentKey, String toIncidentKey) {
    }

    /** 当前依赖图视图：version 为当前图版本，edges 按事件键稳定排序。 */
    public record GraphView(long version, List<GraphEdgeView> edges) {
    }

    /** 提案边条目视图：operation 为 ADD/REMOVE。 */
    public record ProposalEdgeView(String operation, String fromIncidentKey, String toIncidentKey) {
    }

    /** 名册席位视图：incidentKey 仅 COMMANDER 席位有值，SAFETY_REVIEWER 为 null。 */
    public record RosterSeatView(String role, String incidentKey, String person) {
    }

    /** 票决视图。 */
    public record VoteView(String person, String decision, Instant votedAt) {
    }

    /** 边集快照视图：graphVersion 为该快照对应的图版本，edges 按事件键稳定排序。 */
    public record GraphSnapshotView(long graphVersion, List<GraphEdgeView> edges) {
    }

    /**
     * 提案视图：edges 为提案边集（按操作与事件键稳定排序）；roster 为创建时冻结的
     * 不可变名册；votes 为已记录票决；beforeSnapshot/afterSnapshot 仅 ACTIVATED 有值。
     */
    public record ProposalView(String proposalKey, String status, String rationale, String proposer,
                               String safetyReviewer, long expectedGraphVersion,
                               Long appliedGraphVersion, List<ProposalEdgeView> edges,
                               List<RosterSeatView> roster, List<VoteView> votes,
                               GraphSnapshotView beforeSnapshot, GraphSnapshotView afterSnapshot,
                               Instant createdAt, Instant updatedAt) {
    }
}
