package com.example.starter.batch;

import java.time.Instant;

/**
 * 批准响应。
 *
 * @param role        批准角色（QUALITY/OPERATIONS）
 * @param actorId     批准人
 * @param approvedAt  批准时间（UTC）
 * @param batchStatus 批准后批次状态（RELEASE_REVIEW 或 RELEASED）
 */
public record ApprovalResponse(
        ApprovalRole role,
        String actorId,
        Instant approvedAt,
        BatchStatus batchStatus) {
}
