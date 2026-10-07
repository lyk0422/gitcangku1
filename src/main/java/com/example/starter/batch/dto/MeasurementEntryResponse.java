package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * 历史中的测量向量条目：提交时的规范版本、完整向量与整体结论；
 * 换版后旧条目保留但不再作为放行依据。
 */
public record MeasurementEntryResponse(
        int specVersion,
        Map<String, BigDecimal> values,
        TestOutcome outcome,
        String operator,
        Instant createdAt
) {
}
