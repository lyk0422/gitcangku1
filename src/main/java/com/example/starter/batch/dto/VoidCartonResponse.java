package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 作废封箱响应：含释放的数量与标签，以及作废后批次数量守恒视图。
 */
public record VoidCartonResponse(
        String batchKey,
        String cartonKey,
        long labelNo,
        int releasedQuantity,
        String status,
        int version,
        String voidReason,
        Instant voidedAt,
        int sealedQuantity,
        int plannedQuantity,
        int remainingQuantity
) {
}
