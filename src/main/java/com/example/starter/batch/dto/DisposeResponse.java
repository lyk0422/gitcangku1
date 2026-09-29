package com.example.starter.batch.dto;

import com.example.starter.batch.YieldGateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 处置确认结果：确认序号 1/2、已确认该版本的处置人列表及确认后的门禁状态。
 */
public record DisposeResponse(
        String batchKey,
        long version,
        String disposerId,
        int sequence,
        List<String> disposers,
        YieldGateStatus gateStatus,
        BigDecimal deviation,
        BigDecimal limit,
        Instant createdAt
) {
}
