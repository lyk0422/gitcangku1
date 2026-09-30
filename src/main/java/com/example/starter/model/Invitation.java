package com.example.starter.model;

import java.time.Instant;

/**
 * 削峰邀约。
 *
 * @param id               邀约 ID
 * @param periodStart      时段起点（UTC，含）
 * @param periodEnd        时段终点（UTC，不含）
 * @param rateFenPerKwh    每 kWh 奖励（分）
 * @param createdAt        创建时间（UTC）
 */
public record Invitation(
        Long id,
        Instant periodStart,
        Instant periodEnd,
        int rateFenPerKwh,
        Instant createdAt
) {
}
