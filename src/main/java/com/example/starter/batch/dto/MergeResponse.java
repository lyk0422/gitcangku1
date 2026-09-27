package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合批响应。子批初始 QUARANTINED，初始效价为所有父批在合批时刻效价的数量加权平均
 * （按 modelVersion 对应模型舍入）；mergedAt 为 UTC instant。
 * 父批在合批后全部落定 MERGED，不再可用。
 */
public record MergeResponse(
        String batchKey,
        String batchNo,
        String productCode,
        BatchStatus status,
        BigDecimal quantity,
        BigDecimal initialPotency,
        BigDecimal minReleasePotency,
        BigDecimal decayRatePerDay,
        List<MergeParent> parents,
        int modelVersion,
        Instant mergedAt
) {

    /**
     * 合批父批快照：合批时刻效价（potencyAtMerge）与并入权重；parentStatus 恒为 MERGED。
     */
    public record MergeParent(
            String batchKey,
            BigDecimal weight,
            BigDecimal potencyAtMerge,
            BatchStatus parentStatus
    ) {
    }
}
