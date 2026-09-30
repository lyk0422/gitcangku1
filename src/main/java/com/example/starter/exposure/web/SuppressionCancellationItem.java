package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 抑制取消预占明细视图。
 *
 * @param reservationId   被取消的预占单编号（历史可审计，状态为 CANCELLED）
 * @param campaignId      预占所属公告编号
 * @param utcDate         预占额度所属 UTC 日，格式 yyyy-MM-dd
 * @param suppressedAtUtc 所属抑制批次的生效时刻，epoch 毫秒，UTC
 */
public record SuppressionCancellationItem(
        String reservationId,
        String campaignId,
        LocalDate utcDate,
        long suppressedAtUtc
) {
}
