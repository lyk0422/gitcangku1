package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 批次概要响应。producedAt/createdAt 为 UTC instant。
 * temperatureHold 为运输温控冻结门禁（独立于主状态），true 时不得到货放行、拆分、合批或继续移交。
 */
public record BatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        Instant producedAt,
        BatchStatus status,
        boolean temperatureHold,
        List<String> requiredTests,
        Instant createdAt
) {
}
