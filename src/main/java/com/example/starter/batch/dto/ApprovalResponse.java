package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;
import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 批准响应，携带批准序号与批准后的批次状态。
 */
public record ApprovalResponse(
        String batchKey,
        String actorId,
        ApprovalRole role,
        int sequence,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
