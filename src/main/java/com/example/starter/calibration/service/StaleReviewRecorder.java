package com.example.starter.calibration.service;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.ReviewDecision;
import com.example.starter.calibration.repo.ReviewRepository;

/**
 * 失效复核留痕记录器：stale 复核在独立事务中提交，
 * 保证外层复核事务回滚（返回 410）后留痕仍然存在。
 */
@Component
public class StaleReviewRecorder {

    private final ReviewRepository reviews;

    public StaleReviewRecorder(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    /**
     * 在独立事务写入一条 stale=TRUE 的复核留痕。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long outlierId, String requestId, String reviewer, ReviewDecision decision,
                       String reason, int batchRevision, long certificateId) {
        reviews.insert(new OutlierReview(0L, outlierId, requestId, reviewer, decision, reason,
                batchRevision, certificateId, true, Instant.now()));
    }
}
