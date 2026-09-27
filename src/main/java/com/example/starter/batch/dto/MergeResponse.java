package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合批响应：新批概要 + 各父批投入快照 + 固化时刻与加权计算结论。mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String childBatchKey,
        String batchNo,
        String productCode,
        Instant producedAt,
        BatchStatus status,
        List<String> requiredTests,
        BigDecimal quantity,
        PotencyView potency,
        List<MergedParent> parents,
        Instant mergedAt,
        PotencyCalculationView calculation
) {

    /**
     * 合批父批投入及固化时刻该父批的效价输入。
     */
    public record MergedParent(
            String batchKey,
            BigDecimal quantity,
            int modelVersion,
            BigDecimal inputPotency
    ) {
    }
}
