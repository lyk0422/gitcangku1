package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 上机登记响应。prevBatchKey 为同线上一批次，首批为 null；
 * cleaningRequired 表示该批次因前后产品不同而需要批间清洗验证。
 */
public record LineRunResponse(
        String lineCode,
        int seq,
        String batchKey,
        String prevBatchKey,
        String productCode,
        String prevProductCode,
        boolean cleaningRequired,
        Instant createdAt
) {
}