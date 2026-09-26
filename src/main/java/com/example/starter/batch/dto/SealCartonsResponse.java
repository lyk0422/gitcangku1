package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量封箱响应：含本次封箱明细及落定后的数量守恒视图
 * （sealedQuantity 实际已封合计、plannedQuantity 要求值、remainingQuantity 差额）。
 */
public record SealCartonsResponse(
        String batchKey,
        List<CartonResponse> cartons,
        int sealedQuantity,
        int plannedQuantity,
        int remainingQuantity,
        Instant sealedAt
) {
}
