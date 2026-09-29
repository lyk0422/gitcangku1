package com.example.starter.incident.ladder;

import java.time.Instant;
import java.util.List;

/**
 * 升级阈值配置版本实体，对应 escalation_threshold_versions 表，行内容不可变。
 * thresholds 固定为 5 个非负分数门槛，下标 0 对应 1 级，必须严格递增；
 * 等级判定：L(score) = max({1} ∪ {i | score ≥ thresholds[i-1]})，即入阶梯至少 1 级。
 * cooldownMinutes 为降级冷却分钟数：距最近升级必须严格超过该时长才允许降级。
 */
public record ThresholdVersion(
        long id,
        String versionKey,
        List<Long> thresholds,
        int cooldownMinutes,
        String note,
        String createdBy,
        Instant createdAt) {

    /** 阶梯固定级数。 */
    public static final int LEVELS = 5;

    /** 冷却分钟数合法范围（含端点）：0 表示无冷却，上限 527040 = 366 天。 */
    public static final int COOLDOWN_MIN = 0;

    /** 冷却分钟数合法上限（366 天）。 */
    public static final int COOLDOWN_MAX = 366 * 24 * 60;
}
