package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.CanaryLevel;
import com.example.starter.firmware.domain.CanaryPromotion;
import com.example.starter.firmware.domain.ReleaseOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 金丝雀状态视图：当前解锁级别、各级别样本与失败率统计及推进历史。只读，不触发状态变化。
 *
 * @param releaseId      发布单ID
 * @param status         发布单状态
 * @param levelCount     级别总数
 * @param currentLevel   当前已解锁的最高级别
 * @param effectiveRatio 当前生效投放比例（当前解锁级别的比例）
 * @param levels         各级别统计，按级别序号升序
 * @param promotions     推进历史，按推进时间升序
 */
public record CanaryStatusView(long releaseId, String status, int levelCount, int currentLevel,
                               int effectiveRatio, List<LevelView> levels, List<PromotionView> promotions) {

    /**
     * 单级别统计。failureRate 为失败率百分数（向下取整），无样本时为 null。
     */
    public record LevelView(int level, int ratio, int minSamples, int maxFailureRate, boolean unlocked,
                            int samples, int failures, Integer failureRate) {

        public static LevelView of(CanaryLevel level) {
            Integer failureRate = level.sampleCount() == 0
                    ? null : level.failCount() * 100 / level.sampleCount();
            return new LevelView(level.levelNo(), level.ratio(), level.minSamples(), level.maxFailureRate(),
                    level.unlocked(), level.sampleCount(), level.failCount(), failureRate);
        }
    }

    /**
     * 推进历史条目。toLevel 等于级别总数+1 表示该次推进使发布单进入 COMPLETED。
     */
    public record PromotionView(int fromLevel, int toLevel, String promoteKey, int samples, int failures,
                                LocalDateTime promotedAt) {

        public static PromotionView of(CanaryPromotion promotion) {
            return new PromotionView(promotion.fromLevel(), promotion.toLevel(), promotion.promoteKey(),
                    promotion.sampleCount(), promotion.failCount(), promotion.promotedAt());
        }
    }

    public static CanaryStatusView of(ReleaseOrder order, List<CanaryLevel> levels,
                                      List<CanaryPromotion> promotions) {
        return new CanaryStatusView(order.id(), order.status().name(), order.levelCount(), order.unlockedLevel(),
                order.ratio(), levels.stream().map(LevelView::of).toList(),
                promotions.stream().map(PromotionView::of).toList());
    }
}
