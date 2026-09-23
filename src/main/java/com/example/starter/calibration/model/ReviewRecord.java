package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 复核记录：复核员对一个放行批次的驳回操作。reviewKey 全局唯一作为幂等键。
 *
 * @param id                 复核记录 ID（自增）
 * @param reviewKey          复核幂等键，全局唯一
 * @param batchId            被复核的放行批次 ID
 * @param reviewer           复核人（X-Actor-Id），不得为原放行人
 * @param requestFingerprint 复核请求参数规范化指纹（SHA-256），用于同键异参检测
 * @param reviewedAt         复核时间（UTC）
 */
public record ReviewRecord(
        long id,
        String reviewKey,
        String batchId,
        String reviewer,
        String requestFingerprint,
        Instant reviewedAt) {
}
