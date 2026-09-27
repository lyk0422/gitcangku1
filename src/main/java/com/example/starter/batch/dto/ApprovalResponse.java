package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;
import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 批准响应，携带批准序号与批准后的批次状态。
 *
 * <p>第二笔批准（seq=2）放行核验效价时附带实际值/要求值/差额与固化时刻、模型版本；
 * seq=1 或当时未登记效价模型时这些字段为 null（NOT_CONFIGURED 无核验数值）。
 *
 * @param currentPotency   实际值：放行计算时刻当前效价
 * @param thresholdPotency 要求值：当时最低放行效价
 * @param difference       差额：实际值 - 要求值，非负才放行
 */
public record ApprovalResponse(
        String batchKey,
        String actorId,
        ApprovalRole role,
        int sequence,
        BatchStatus batchStatus,
        Integer potencyModelVersion,
        Instant potencyEvaluatedAt,
        BigDecimal currentPotency,
        BigDecimal thresholdPotency,
        BigDecimal difference,
        Instant createdAt
) {
}
