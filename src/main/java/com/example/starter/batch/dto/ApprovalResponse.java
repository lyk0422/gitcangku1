package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;
import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 批准响应，携带批准序号与批准后的批次状态。
 * 第二次批准（RELEASED）且批次登记了效价模型时，potencyCalculation 为放行固化计算；
 * 第一次批准或未登记效价模型时为 null（null 语义保持稳定）。
 */
public record ApprovalResponse(
        String batchKey,
        String actorId,
        ApprovalRole role,
        int sequence,
        BatchStatus batchStatus,
        Instant createdAt,
        PotencyCalculationView potencyCalculation
) {
}
