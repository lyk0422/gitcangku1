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
     * 升级阶梯阈值配置视图：thresholds 为 1~5 级进入阈值（含），等级区间左闭右开；
     * cooldownMinutes 为降级冷却分钟数。
     */
    public record LadderConfigView(int configVersion, List<Integer> thresholds, int cooldownMinutes,
                                   String createdBy, Instant createdAt) {
    }

    /**
     * 阶梯当前状态视图（含冷却诊断）：事件尚无分数时 riskScore/currentLevel/configVersion/
     * cooldownStartAt/cooldownRemainingMinutes/lastOperator/updatedAt 为 null；
     * 无配置时 cooldownMinutes 为 null。cooldownRemainingMinutes 为查询时刻的剩余冷却分钟数（0 表示已过）。
     */
    public record LadderStateView(String incidentKey, Integer riskScore, Integer currentLevel,
                                  Integer configVersion, Instant cooldownStartAt,
                                  Integer cooldownMinutes, Long cooldownRemainingMinutes,
                                  String lastOperator, Instant updatedAt) {
    }

    /**
     * 阶梯等级变更历史视图：各字段为变更时快照，不回写；按发生顺序返回。
     */
    public record LadderHistoryEntryView(long id, String changeType, int fromLevel, int toLevel,
                                         int riskScore, int configVersion, String actor,
                                         Instant occurredAt) {
    }

    /** 阶梯历史列表视图：entries 按发生顺序（落库顺序）返回，排序稳定。 */
    public record LadderHistoryView(String incidentKey, List<LadderHistoryEntryView> entries) {
    }

    /**
     * 单事件分数更新结果：score 为实际值；nextLevelThreshold 为进入下一级所需阈值（要求值），
     * gapToNextLevel 为差额（nextLevelThreshold - score）；已在最高级时两者为 null。
     */
    public record LadderScoreResultView(String incidentKey, int score, int previousLevel,
                                        int currentLevel, boolean escalated,
                                        Integer nextLevelThreshold, Integer gapToNextLevel) {
    }

    /** 批量分数更新结果：configVersion 为本批定级使用的配置版本；results 按事件键排序。 */
    public record LadderScoreBatchView(int configVersion, List<LadderScoreResultView> results) {
    }

    /**
     * 降级冷却明细：requiredMinutes 为要求值（冷却分钟数）、elapsedMinutes 为实际值
     * （距冷却起点已过分钟数）、remainingMinutes 为差额（剩余分钟数）。
     */
    public record CooldownDetail(long requiredMinutes, long elapsedMinutes, long remainingMinutes) {
    }
}
