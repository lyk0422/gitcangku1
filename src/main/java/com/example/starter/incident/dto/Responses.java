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

    /** 依赖边视图：fromIncidentKey 的任务依赖 toIncidentKey；source 为 TASK/PROPOSAL。 */
    public record DependencyEdgeView(String fromIncidentKey, String toIncidentKey,
                                     String source, Long refId) {
    }

    /** 提案中的结构化边变更视图：op 为 ADD/DELETE，列表稳定排序、换序等价。 */
    public record EdgeChangeView(String op, String fromIncidentKey, String toIncidentKey) {
    }

    /** 名册席位视图：role 为 COMMANDER/SAFETY_REVIEWER；COMMANDER 席位带 incidentKey。 */
    public record RosterEntryView(String personId, String role, String incidentKey) {
    }

    /** 票决视图：按人员一票。 */
    public record VoteView(String personId, String choice, Instant votedAt) {
    }

    /**
     * 提案视图：含状态、期望/激活图版本、规范化变更、冻结名册与已投票决（稳定排序）。
     * beforeEdges/afterEdges 仅 ACTIVATED 有值（前后边集快照）。
     */
    public record ProposalView(String proposalKey, long expectedGraphVersion,
                               String status, String businessNote, String safetyReviewer,
                               String createdBy, Instant createdAt, Instant activatedAt,
                               Long activatedGraphVersion,
                               List<EdgeChangeView> changes,
                               List<RosterEntryView> roster,
                               List<VoteView> votes,
                               List<DependencyEdgeView> beforeEdges,
                               List<DependencyEdgeView> afterEdges) {
    }

    /** 图版本证据视图：当前版本号、当前全量边（稳定排序）及在该版本生效的提案键（无则 null）。 */
    public record GraphEvidenceView(long graphVersion, List<DependencyEdgeView> edges,
                                    String activatedProposalKey) {
    }
}
