package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CanaryLevel;
import com.example.starter.firmware.domain.CanaryPromotion;
import com.example.starter.firmware.domain.ReleaseOrder;

import java.util.List;

/**
 * 金丝雀分级状态视图：当前解锁级别、各级别样本与失败率统计及推进历史。只读，不触发状态变化。
 */
public record CanaryStatusView(long releaseId, String status, int currentLevel, int maxLevel,
                               List<LevelView> levels, List<PromotionView> promotions) {

    /**
     * 单级别统计视图。
     *
     * @param unlocked           是否已解锁（levelNo 不大于当前解锁级别）
     * @param failureRatePercent 失败率百分比，保留两位小数；无样本时为0
     */
    public record LevelView(int levelNo, int ratio, int minSamples, int maxFailureRate,
                            int sampleCount, int failedCount, double failureRatePercent,
                            boolean unlocked) {
    }

    /**
     * 推进历史视图。toLevel 为 null 表示该次推进进入 COMPLETED 终态。
     */
    public record PromotionView(int fromLevel, Integer toLevel, String action,
                                int sampleCount, int failedCount, String promoteKey) {
    }

    public static CanaryStatusView of(ReleaseOrder order, List<CanaryLevel> levels,
                                      List<CanaryPromotion> promotions) {
        List<LevelView> levelViews = levels.stream()
                .map(level -> new LevelView(level.levelNo(), level.ratio(), level.minSamples(),
                        level.maxFailureRate(), level.sampleCount(), level.failedCount(),
                        level.failureRatePercent(), level.levelNo() <= order.currentLevel()))
                .toList();
        List<PromotionView> promotionViews = promotions.stream()
                .map(p -> new PromotionView(p.fromLevel(), p.toLevel(), p.action(),
                        p.sampleCount(), p.failedCount(), p.promoteKey()))
                .toList();
        int maxLevel = levels.isEmpty() ? 0 : levels.get(levels.size() - 1).levelNo();
        return new CanaryStatusView(order.id(), order.status().name(), order.currentLevel(), maxLevel,
                levelViews, promotionViews);
    }
}
