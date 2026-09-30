package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 升级阶梯阈值配置实体，对应 incident_ladder_configs 表，版本化追加、旧版本不回写。
 * thresholds 为 1~5 级进入阈值（含），必须严格递增；等级区间左闭右开，
 * 即 k 级覆盖分数区间 [thresholds[k-1], thresholds[k])，5 级覆盖 [thresholds[4], ∞)。
 * cooldownMinutes 为降级冷却分钟数（1~10080），冷却区间 [冷却起点, 起点+冷却分钟数) 左闭右开。
 */
public record LadderConfig(
        long id,
        int configVersion,
        List<Integer> thresholds,
        int cooldownMinutes,
        String createdBy,
        Instant createdAt) {

    /** 阶梯级数（1~5 级，对应 5 个阈值）。 */
    public static final int LEVEL_COUNT = 5;

    public LadderConfig {
        thresholds = List.copyOf(thresholds);
        if (thresholds.size() != LEVEL_COUNT) {
            throw new IllegalArgumentException("阈值必须恰好 " + LEVEL_COUNT + " 个");
        }
    }

    /**
     * 计算分数所在等级：满足 score >= thresholds[k] 的最高级（区间左闭右开）；
     * 未达 1 级阈值返回 0（未入阶梯）。
     */
    public int levelFor(int score) {
        int level = 0;
        for (Integer threshold : thresholds) {
            if (score >= threshold) {
                level++;
            } else {
                break;
            }
        }
        return level;
    }

    /**
     * 进入下一等级所需的阈值（要求值）；已在最高级返回 null。
     */
    public Integer nextThreshold(int currentLevel) {
        return currentLevel >= LEVEL_COUNT ? null : thresholds.get(currentLevel);
    }
}
