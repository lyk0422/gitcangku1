package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核响应。status 为 STALE 时 HTTP 状态为 410；reviewsReceived/reviewsRequired
 * 分别表示当前有效复核人数（实际值）与裁决所需人数（要求值）。
 *
 * @param id              复核记录 ID
 * @param outlierId       离群记录 ID
 * @param reviewer        复核人
 * @param decision        复核结论：ACCEPT / REJECT
 * @param reason          复核理由；未填写为 null
 * @param status          复核状态：VALID / STALE
 * @param outlierStatus   复核后离群记录状态：PENDING / ACCEPTED / REJECTED
 * @param reviewsReceived 当前有效复核人数（实际值）
 * @param reviewsRequired 裁决所需人数（要求值，固定为 2）
 * @param createdAt       复核时间（UTC）
 */
public record ReviewResponse(
        long id,
        long outlierId,
        String reviewer,
        String decision,
        String reason,
        String status,
        String outlierStatus,
        int reviewsReceived,
        int reviewsRequired,
        Instant createdAt) {
}