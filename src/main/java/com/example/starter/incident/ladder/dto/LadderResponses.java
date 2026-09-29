package com.example.starter.incident.ladder.dto;

import java.time.Instant;
import java.util.List;

/**
 * 升级阶梯读/写接口响应体集合。所有时间字段均为 UTC ISO-8601，微秒精度；
 * 为空语义见各字段注释。
 */
public final class LadderResponses {

    private LadderResponses() {
    }

    /** 阈值版本视图：行不可变，publishedAt 为发布 UTC 时刻。 */
    public record ThresholdVersionView(String versionKey, List<Long> thresholds, int cooldownMinutes,
                                       String createdBy, String note, Instant publishedAt) {
    }

    /**
     * 事件阶梯明细视图。
     * registered=false 表示尚未登记分数：level/score/cooldown 等字段均为空；
     * actualLevel 为当前等级；requiredLevel 为按当前生效版本与当前分数应处等级；
     * 二者差 levelGap = requiredLevel - actualLevel（实际值与要求值的等级差额），
     * 正数表示等级滞后于分数，负数表示经人工降级后高于分数要求；
     * cooldownElapsedSeconds 为距最近升级已过秒数（实际值），
     * cooldownRequiredSeconds/Minutes 为当前依据版本要求秒数/配置分钟数（要求值），
     * cooldownRemainingSeconds = max(0, 要求秒数 - 已过秒数)（差额），
     * 左闭右开边界下降级仅在生效时刻严格晚于 冷却起点+要求秒数 时可执行；
     * lastEscalatedAt/By 为冷却起点与操作人。
     */
    public record LadderDetailView(String incidentKey, boolean registered, Long score, Integer actualLevel,
                                   Integer requiredLevel, Integer levelGap,
                                   String thresholdVersionKey, Instant lastEscalatedAt,
                                   String lastEscalatedBy, Long cooldownElapsedSeconds,
                                   Integer cooldownRequiredMinutes, Long cooldownRequiredSeconds,
                                   Long cooldownRemainingSeconds,
                                   boolean demotable, String blockReason, Instant updatedAt) {
    }

    /** 批量分数更新单项结果：记录更新前后分数/等级、裁决阈值版本与变更类型。 */
    public record BatchScoreResultItem(String incidentKey, Long fromScore, long toScore,
                                       Integer fromLevel, int toLevel, String changeType,
                                       String thresholdVersionKey) {
    }

    /**
     * 批量分数更新完整响应（同键重放返回该首次快照）：
     * results 顺序与请求 items 顺序一致；appliedCount 为整批项数；
     * escalated/unchanged/initialised 为各类计数（实际值）；totalScoreDelta 为分数变化合计
     * （新登记事件按 fromScore 为空、差额 = toScore 计入）。
     */
    public record BatchScoreResponse(String commandKey, List<BatchScoreResultItem> results,
                                     int appliedCount, int escalated, int unchanged, int initialised,
                                     long totalScoreDelta, String thresholdVersionKey) {
    }

    /**
     * 降级完整响应（同键重放返回该首次快照）。
     * cooldownElapsedSeconds 为降级时刻距最近升级的实际已过秒数（实际值），
     * cooldownRequiredSeconds/Minutes 为依据版本要求秒数与配置分钟数（要求值），
     * 成功时已严格超过要求，cooldownOvertimeSeconds 为超出秒数（差额，始终 ≥ 0）。
     */
    public record DemoteResponse(String incidentKey, int fromLevel, int toLevel, long score,
                                 String actor, String thresholdVersionKey, Instant demotedAt,
                                 long cooldownElapsedSeconds, int cooldownRequiredMinutes,
                                 long cooldownRequiredSeconds, long cooldownOvertimeSeconds) {
    }

    /** 历史行视图：字段与表列一一对应；fromLevel/fromScore 仅 INIT 行为空。 */
    public record LadderHistoryItemView(long id, String changeType, Integer fromLevel, int toLevel,
                                        Long fromScore, long toScore, String thresholdVersionKey,
                                        String actor, Instant cooldownStartAt, String commandKey,
                                        Instant occurredAt) {
    }

    /** 事件阶梯历史视图：items 按落库顺序（id 升序），排序稳定。 */
    public record LadderHistoryView(String incidentKey, List<LadderHistoryItemView> items) {
    }

    /**
     * 诊断视图：对全部已登记事件按"要求等级降序、事件键升序"稳定排序输出；
     * thresholdVersionKey 为当前生效版本（无版本时为空）；
     * registeredCount 为已登记事件数量（实际值），aboveRequiredCount 为等级滞后于分数的数量，
     * aboveRequiredRatio 为后者占前者的比例（实际值，0~1，无登记事件时为 0）。
     */
    public record LadderDiagnosticsView(String thresholdVersionKey, int registeredCount,
                                        int aboveRequiredCount, double aboveRequiredRatio,
                                        List<LadderDetailView> items) {
    }
}
