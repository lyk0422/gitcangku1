package com.example.starter.billing.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 承诺视图。
 *
 * @param commitmentId 承诺 ID
 * @param offerId      邀约 ID
 * @param userId       用户 ID
 * @param committedKwh 承诺减少用电量，单位 kWh
 * @param requestId    承诺幂等键
 * @param createdAt    创建时间（UTC）
 */
public record CommitmentResponse(
        Long commitmentId,
        String offerId,
        String userId,
        BigDecimal committedKwh,
        String requestId,
        Instant createdAt) {
}
