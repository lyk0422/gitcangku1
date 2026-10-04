package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 生产线加工顺序登记条目。prevBatchKey 为前一批次业务键，首批为 null；
 * createdAt 为 UTC instant。
 */
public record LineSequenceResponse(
        String lineId,
        int seq,
        String batchKey,
        String prevBatchKey,
        String operatorId,
        Instant createdAt
) {
}