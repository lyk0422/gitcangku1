package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 同行复核记录，写入后不可变，固化被复核版本、证书版本、结论、说明与时刻。
 *
 * @param id            复核记录 ID（自增）
 * @param reviewKey     复核业务键，全局唯一（幂等键）
 * @param measurementKey 被复核测量的业务键
 * @param version       被复核的测量版本号（固化，不随修订迁移）
 * @param measurementId 被复核的测量版本记录 ID
 * @param certificateId 复核时测量关联的证书 ID（证书版本固化）
 * @param conclusion    复核结论：PASS 通过 / RETURN 退回
 * @param state         记录状态：VALID 有效 / STALE 失效（版本变化时写入）
 * @param reviewer      复核人（X-Actor-Id），必须不同于提交人
 * @param comment       复核说明
 * @param createdAt     复核提交时间（UTC）
 */
public record PeerReview(
        long id,
        String reviewKey,
        String measurementKey,
        int version,
        long measurementId,
        long certificateId,
        ReviewConclusion conclusion,
        ReviewState state,
        String reviewer,
        String comment,
        Instant createdAt) {
}
