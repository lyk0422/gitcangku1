package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 离群明细中的复核条目。
 *
 * @param reviewer  复核人
 * @param decision  复核结论：ACCEPT / REJECT
 * @param reason    复核理由；未填写为 null
 * @param status    复核状态：VALID / STALE
 * @param createdAt 复核时间（UTC）
 */
public record ReviewItem(
        String reviewer,
        String decision,
        String reason,
        String status,
        Instant createdAt) {
}