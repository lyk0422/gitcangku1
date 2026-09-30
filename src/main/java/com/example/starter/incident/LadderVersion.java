package com.example.starter.incident;

import java.time.Instant;

/**
 * 升级阶梯阈值版本实体，对应 ladder_versions 表，落库后不可变。
 * 五个阈值严格递增且与分数共同确定等级，区间左闭右开：
 * [threshold1, threshold2) 为 1 级，……，[threshold5, +∞) 为 5 级，低于 threshold1 不在阶梯上。
 * cooldownMinutes 为距最近一次升级后允许降级的冷却分钟数，取值范围 0~10080（含端点）。
 * 后续新建版本不回写任何事件已有的等级与历史。
 */
public record LadderVersion(
        long id,
        String versionKey,
        int threshold1,
        int threshold2,
        int threshold3,
        int threshold4,
        int threshold5,
        int cooldownMinutes,
        String createdBy,
        Instant createdAt) {

    /**
     * 按本版本阈值计算分数对应的阶梯等级；低于一级阈值返回 null（未进入阶梯）。
     */
    public Integer levelOf(int score) {
        if (score >= threshold5) {
            return 5;
        }
        if (score >= threshold4) {
            return 4;
        }
        if (score >= threshold3) {
            return 3;
        }
        if (score >= threshold2) {
            return 2;
        }
        if (score >= threshold1) {
            return 1;
        }
        return null;
    }

    /**
     * 返回指定等级的入级阈值（1~5）。
     */
    public int thresholdOfLevel(int level) {
        return switch (level) {
            case 1 -> threshold1;
            case 2 -> threshold2;
            case 3 -> threshold3;
            case 4 -> threshold4;
            case 5 -> threshold5;
            default -> throw new IllegalArgumentException("等级必须为 1~5: " + level);
        };
    }

    /**
     * 按级别顺序返回五个阈值，用于规范化指纹与视图。
     */
    public int[] thresholds() {
        return new int[] {threshold1, threshold2, threshold3, threshold4, threshold5};
    }
}
