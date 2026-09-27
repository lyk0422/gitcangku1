package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReceiptReview;

/**
 * 撤销链回执复核视图。reviewedAtUtc 为 UTC 时刻，待复核时为 null。
 */
public record ReviewView(long reviewId, long taskId, long chainId, Integer chainVersion,
                         String status, String rejectReason, String reviewedBy, String reviewedAtUtc) {

    public static ReviewView of(ReceiptReview review, Integer chainVersion) {
        return new ReviewView(review.id(), review.taskId(), review.chainId(), chainVersion,
                review.status().name(), review.rejectReason(), review.reviewedBy(),
                review.reviewedAt() == null ? null : review.reviewedAt().toString());
    }
}
