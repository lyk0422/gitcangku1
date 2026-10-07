package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 测量向量检验响应，携带针对的规范版本与提交后的批次状态。
 */
public record MeasurementResponse(
        String batchKey,
        String measurementKey,
        String specKey,
        int specVersion,
        List<BigDecimal> values,
        String outcome,
        String operator,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
