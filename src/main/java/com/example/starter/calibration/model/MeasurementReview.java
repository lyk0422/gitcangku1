package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 同行复核记录。不可变：固化被复核的测量修订版本、证书、结论、说明与时刻。
 * 记录是否“有效”由 {@code status == VALID} 且 {@code measurementRevision} 等于测量当前版本共同判定。
 *
 * @param id                   复核记录 ID（自增）
 * @param reviewKey            业务复核键，全局唯一（幂等键）
 * @param requestId            提交时的请求 ID（追溯用）
 * @param measurementId        被复核的测量记录 ID
 * @param measurementRevision  被复核的测量修订版本号（固化）
 * @param certificateId        复核时测量关联的证书 ID（固化）
 * @param reviewer             复核人，须不同于测量提交人
 * @param conclusion           复核结论：PASS / RETURN
 * @param comment              复核说明
 * @param status               记录状态：VALID / STALE
 * @param createdAt            复核提交时间（UTC）
 */
public record MeasurementReview(
        long id,
        String reviewKey,
        String requestId,
        long measurementId,
        int measurementRevision,
        long certificateId,
        String reviewer,
        ReviewConclusion conclusion,
        String comment,
        ReviewStatus status,
        Instant createdAt) {
}
