package com.example.starter.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户对邀约的有效承诺：每个用户对每个邀约至多一条。
 *
 * @param id             承诺自增主键
 * @param offerId        邀约 ID
 * @param userId         用户 ID
 * @param committedKwh   承诺减少用电量，单位 kWh，上限 50 kWh，大于 0
 * @param requestId      承诺请求的幂等键
 * @param createdAt      创建时间（UTC）
 */
public record Commitment(
        Long id,
        String offerId,
        String userId,
        BigDecimal committedKwh,
        String requestId,
        Instant createdAt) {
}
