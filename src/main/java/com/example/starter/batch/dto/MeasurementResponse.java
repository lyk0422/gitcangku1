package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.TestOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 测量向量检验响应。outcome 为整体结论：全部约束满足（含等号）才 PASS；
 * violatedConstraints 为越界约束序号（从 1 开始），PASS 时为空表。
 */
public record MeasurementResponse(
        String batchKey,
        int specVersion,
        Map<String, BigDecimal> values,
        TestOutcome outcome,
        List<Integer> violatedConstraints,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
