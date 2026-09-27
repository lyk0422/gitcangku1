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
     * cancelledBy/cancelledAt 仅 CANCELLED 有值；version 自 1 起，每次修订加 1。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           int version, List<TaskBlockerView> blockers, String createdBy,
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
     * 决策提案视图：requiredRoles 为登记时固化的有序角色集合；
     * supportCount/opposeCount 为当前任务版本下 CAST 票统计，remainingToQuorum 为距法定人数差额；
     * decidedTaskVersion 仅 EFFECTIVE 有值，decidedAt 为生效/到期判定时刻（OPEN 为空）。
     */
    public record DecisionView(String proposalKey, String taskKey, String taskAction,
                               int currentTaskVersion, List<String> requiredRoles, int quorum,
                               int supportCount, int opposeCount, int remainingToQuorum,
                               String status, Integer decidedTaskVersion, Instant decidedAt,
                               Instant expiresAt, String createdBy, Instant createdAt) {
    }

    /**
     * 投票视图：taskVersion/castAt 为投票时固化的任务版本与 UTC 时刻，不被后续操作改写；
     * status 为 CAST/WITHDRAWN/STALE，updatedAt 为状态最近变更时刻（未变更等于 castAt）。
     */
    public record VoteView(String role, String ballot, int taskVersion, String status,
                           String actor, Instant castAt, Instant updatedAt) {
    }

    /** 提案完整历史：提案当前视图 + 全部投票证据（按投票顺序，含 WITHDRAWN/STALE）。 */
    public record DecisionHistoryView(DecisionView decision, List<VoteView> votes) {
    }

    /** 按事件分组的提案列表视图：decisions 按登记顺序返回。 */
    public record DecisionListView(String incidentKey, List<DecisionView> decisions) {
    }

    /**
     * 提案诊断视图：各状态票数与法定人数差额；读取本身不改变任何状态。
     */
    public record DecisionDiagnosticsView(String proposalKey, String status, int quorum,
                                          int supportCount, int opposeCount, int withdrawnCount,
                                          int staleCount, int remainingToQuorum,
                                          Instant expiresAt, Instant decidedAt) {
    }
}
