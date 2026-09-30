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
     * 阈值版本视图：thresholds 固定为 1~5 级入级阈值（严格递增）；
     * cooldownMinutes 为降级冷却分钟数。
     */
    public record LadderVersionView(String versionKey, List<Integer> thresholds,
                                    int cooldownMinutes, String createdBy, Instant createdAt) {
    }

    /**
     * 阶梯等级变更历史条目：changeType 为 PROMOTE/DEMOTE；
     * fromLevel 在首次进入阶梯时为 null，其余为变更前等级；
     * score/cooldownSince/thresholdVersionKey 记录变更当时的不可变判定上下文；
     * cooldownSince 始终为最近一次升级时刻（降级不刷新）。
     */
    public record LadderHistoryView(String changeType, Integer fromLevel, int toLevel,
                                    Integer score, String thresholdVersionKey, String operator,
                                    Instant cooldownSince, Instant occurredAt) {
    }

    /**
     * 不可变风险分数证据视图：每次分数更新一条，按提交顺序返回；
     * evaluatedLevel 为按当时阈值版本与左闭右开区间算出的等级，未进入阶梯为 null。
     */
    public record LadderScoreView(int score, String thresholdVersionKey, Integer evaluatedLevel,
                                  String submittedBy, Instant occurredAt) {
    }

    /**
     * 事件升级阶梯视图：onLadder 表示是否已进入阶梯；currentLevel 为当前等级（未进入为 null，
     * 不会因分数下降自动降低）；lastScore 为最近提交分数；thresholdVersionKey 为最近一次
     * 分数更新使用的版本；lastPromotedAt 为冷却起点（最近升级时刻，未升级为 null）；
     * demoteReady 表示当前时刻降级冷却是否已满足；demoteCooldownRemainingMinutes 为距满足
     * 冷却还差的分钟数（实际值，已满足为 0）；history 按发生顺序返回且不可变；
     * scores 为全部不可变分数证据，后续配置修订不回写。
     */
    public record LadderView(String incidentKey, boolean onLadder, Integer currentLevel,
                             Integer lastScore, String thresholdVersionKey, Instant lastPromotedAt,
                             boolean demoteReady, long demoteCooldownRemainingMinutes,
                             List<LadderHistoryView> history, List<LadderScoreView> scores) {
    }

    /**
     * 批量分数更新的单事件结果：actualLevel 为该事件最终等级（实际值，未进入阶梯为 null），
     * requiredLevel 为按本次分数与阈值要求进入的等级，levelDelta 为实际相对要求的差额
     * （actualLevel - requiredLevel，未进入阶梯且要求在梯上时为负）；changed 为本次是否产生升级；
     * previousLevel 为更新前等级（未进入阶梯为 null）。
     */
    public record ScoreUpdateResultView(String incidentKey, int score, Integer previousLevel,
                                        Integer requiredLevel, Integer actualLevel, int levelDelta,
                                        boolean changed) {
    }

    /** 批量分数更新响应：results 按请求中事件键的规范化顺序（事件键升序）返回。 */
    public record ScoreBatchView(String thresholdVersionKey, List<ScoreUpdateResultView> results) {
    }
}
