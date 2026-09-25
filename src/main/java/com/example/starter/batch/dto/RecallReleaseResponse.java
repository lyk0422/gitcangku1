package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 召回解除申请响应。status 为 PENDING/APPROVED；decidedAt 未批准时为 null。
 */
public record RecallReleaseResponse(
        String releaseKey,
        String batchKey,
        int recallVersion,
        String correctiveMeasures,
        List<String> retestBatches,
        String approver,
        String applicant,
        String status,
        Instant createdAt,
        Instant decidedAt
) {
}
