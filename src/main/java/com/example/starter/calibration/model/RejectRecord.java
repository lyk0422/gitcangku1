package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 驳回记录。仅未放行测量可驳回；驳回后可经重算修订回到待放行。历史只增。
 *
 * @param id            驳回记录 ID（自增）
 * @param measurementId 被驳回的测量 ID
 * @param rejectedBy    驳回人（X-Actor-Id）
 * @param reason        驳回原因
 * @param rejectedAt    驳回时间（UTC）
 */
public record RejectRecord(
        long id,
        long measurementId,
        String rejectedBy,
        String reason,
        Instant rejectedAt) {
}
