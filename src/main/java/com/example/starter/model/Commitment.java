package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 用户削峰承诺；每个用户每个邀约最多一条有效承诺。
 *
 * @param id            承诺 ID
 * @param invitationId  邀约 ID
 * @param userId        用户 ID
 * @param reductionKwh  承诺减少电量（kWh，0 &lt; 值 &le; 50）
 * @param requestId     承诺幂等 requestId
 * @param createdAt     创建时间（UTC）
 */
public record Commitment(
        Long id,
        Long invitationId,
        String userId,
        BigDecimal reductionKwh,
        String requestId,
        Instant createdAt
) {
}
