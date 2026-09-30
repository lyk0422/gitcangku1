package com.example.starter.calibration.service;

import java.util.List;

import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 离群复核法定人数纯逻辑：至少两名有效（非 STALE）复核人、且两人结论相同才形成裁决；
 * 结论相反或不足两人时保持 PENDING（含待仲裁）。追加第三名不同复核人可打破相反僵局。
 *
 * <p>规则计数：有效 ACCEPT 达到 2 即 ACCEPTED；有效 REJECT 达到 2 即 REJECTED；否则 PENDING。
 * 写路径在裁决形成后拒绝继续追加复核，因此不会出现两方都达到 2 的状态。
 */
public final class QuorumRules {

    /** 形成有效裁决所需的最少同议有效复核人数。 */
    public static final int REQUIRED_REVIEWERS = 2;

    private QuorumRules() {
    }

    /**
     * 基于有效复核集合（已排除 STALE、且复核人互不相同）计算裁决状态。
     */
    public static OutlierStatus evaluate(List<OutlierReview> activeReviews) {
        int accepts = 0;
        int rejects = 0;
        for (OutlierReview review : activeReviews) {
            if (review.stale()) {
                continue;
            }
            if (review.decision() == ReviewDecision.ACCEPT) {
                accepts++;
            } else {
                rejects++;
            }
        }
        if (accepts >= REQUIRED_REVIEWERS) {
            return OutlierStatus.ACCEPTED;
        }
        if (rejects >= REQUIRED_REVIEWERS) {
            return OutlierStatus.REJECTED;
        }
        return OutlierStatus.PENDING;
    }

    /**
     * 裁决形成所需的差额：返回“还需多少名与给定结论一致的有效复核”。
     * 数量语义：实际值=当前同议数，要求值=2，差额=要求-实际（不为负）。
     */
    public static int missingSameVotes(List<OutlierReview> activeReviews, ReviewDecision decision) {
        long actual = activeReviews.stream()
                .filter(r -> !r.stale() && r.decision() == decision)
                .count();
        return Math.max(0, REQUIRED_REVIEWERS - (int) actual);
    }
}
