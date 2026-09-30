package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 首次结算记录；固化读数版本与奖励金额，读数更正不覆盖本条。
 *
 * @param id             结算 ID
 * @param invitationId   邀约 ID
 * @param userId         用户 ID
 * @param commitmentId   对应承诺 ID
 * @param readingVersion 首次结算所依据的已提交读数版本
 * @param rewardKwh      奖励电量（kWh，按 0.1 kWh 向下截取）
 * @param amountFen      奖励金额（分，整数）
 * @param requestId      结算幂等 requestId
 * @param createdAt      结算时间（UTC）
 */
public record Settlement(
        Long id,
        Long invitationId,
        String userId,
        Long commitmentId,
        int readingVersion,
        BigDecimal rewardKwh,
        long amountFen,
        String requestId,
        Instant createdAt
) {
}
