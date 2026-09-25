package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 复核记录响应。复核记录不可变，固化被复核版本、证书、结论、说明与时刻。
 *
 * @param reviewKey            业务复核键
 * @param measurementKey       被复核的测量键
 * @param measurementRevision  被复核的测量修订版本号
 * @param certificateId        复核时测量关联的证书 ID
 * @param reviewer             复核人
 * @param conclusion           复核结论：PASS / RETURN
 * @param comment              复核说明
 * @param status               记录状态：VALID / STALE
 * @param effective            当前是否有效（VALID 且版本等于测量当前版本）
 * @param createdAt            复核提交时间（UTC）
 */
public record ReviewResponse(
        String reviewKey,
        String measurementKey,
        int measurementRevision,
        long certificateId,
        String reviewer,
        String conclusion,
        String comment,
        String status,
        boolean effective,
        Instant createdAt) {
}
