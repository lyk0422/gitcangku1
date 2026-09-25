package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 到期批次清单项。expired 标识不影响批次状态本身，状态原样返回。
 */
public record ExpiredBatchResponse(
        String batchKey,
        String productCode,
        String batchNo,
        BatchStatus status,
        Instant expiresAt
) {
}
