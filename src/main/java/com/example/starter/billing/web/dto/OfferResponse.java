package com.example.starter.billing.web.dto;

import java.time.Instant;

/**
 * 邀约视图。
 *
 * @param offerId   邀约 ID
 * @param startTime 时段起点（UTC，左闭）
 * @param endTime   时段终点（UTC，右开）
 * @param createdAt 创建时间（UTC）
 */
public record OfferResponse(
        String offerId,
        Instant startTime,
        Instant endTime,
        Instant createdAt) {
}
