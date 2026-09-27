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
     * 处置任务视图：revision 为任务当前版本（创建为 1，每修订一次 +1，提案按版本计票）；
     * blockers 按阻塞事件键排序；doneBy/doneAt 仅 DONE 有值，
     * cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, int revision, String groupCode, String title,
                           String status, List<TaskBlockerView> blockers, String createdBy,
                           Instant createdAt, String doneBy, Instant doneAt,
                           String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 投票明细视图：taskRevision 为该票针对的任务版本；votedAt 为投票 UTC 时刻；
     * staleAt 仅 STALE 有值（任务版本变化时刻）；withdrawnAt 仅 WITHDRAWN 有值，否则为 null。
     */
    public record VoteView(long id, int taskRevision, String voterActor, String roleCode,
                           String choice, String status, Instant votedAt,
                           Instant staleAt, Instant withdrawnAt) {
    }

    /**
     * 提案视图：requiredRoles 为登记时固化的角色集合（按角色编码升序）；
     * taskRevision 为查询时任务的当前版本（历史证据字段另行固化在 effectiveTaskRevision）；
     * supportVotes/opposeVotes 为当前版本 CAST 票数；quorum 为法定人数；
     * remainingVotes 为距法定人数的差额（实际支持票不足时为正数，已达成为 0）；
     * effectiveAt/effectiveBy/effectiveTaskRevision 仅 EFFECTIVE 有值，expiredAt 仅 EXPIRED 有值。
     */
    public record ProposalView(String proposalKey, String taskKey, String decisionType,
                               String status, List<String> requiredRoles, int quorum,
                               int taskRevision, int baseTaskRevision,
                               int supportVotes, int opposeVotes, int remainingVotes,
                               String createdBy, Instant createdAt, Instant expiresAt,
                               Instant effectiveAt, String effectiveBy,
                               Integer effectiveTaskRevision, Instant expiredAt,
                               List<VoteView> votes) {
    }

    /**
     * 提案诊断视图（只读）：now 为服务端注入时钟的当前 UTC 时刻；
     * votingOpen 为当前是否处于左闭右开投票区间 [createdAt, expiresAt)；
     * secondsRemaining 为距到期的剩余秒数（实际值，要求窗口时长 - 已耗时长；到期后为 0）；
     * activeCastVotes 为当前版本有效票数，activeRoleCount 为已投票角色数，
     * requiredRoleCount 为要求角色数，missingRoles 为尚未投票的要求角色（实际差额）。
     */
    public record ProposalDiagnosticsView(String proposalKey, String status, Instant now,
                                          Instant expiresAt, boolean votingOpen,
                                          long secondsRemaining, int quorum,
                                          int supportVotes, int opposeVotes, int remainingVotes,
                                          int activeCastVotes, int activeRoleCount,
                                          int requiredRoleCount, List<String> missingRoles,
                                          int taskRevision, int baseTaskRevision) {
    }

    /** 事件的提案列表视图：proposals 按登记顺序（id 升序）稳定返回。 */
    public record DecisionListView(String incidentKey, List<ProposalView> proposals) {
    }
}
