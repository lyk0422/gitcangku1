package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 召回解除申请状态响应。status 为 PENDING/APPROVED；
 * approver 与 decidedAt 仅批准后有值，PENDING 时为 null。
 */
public record RecallReleaseResponse(
        String releaseKey,
        String batchKey,
        int recallVersion,
        String correctiveAction,
        List<String> reinspectionBatches,
        String applicant,
        String status,
        String approver,
        Instant decidedAt,
        Instant createdAt
) {
}
