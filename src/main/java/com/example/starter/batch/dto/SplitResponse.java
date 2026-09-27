package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant。
 * 父批已登记效价时：子批初始效价为父批在拆分时刻的效价（按 modelVersion 舍入），
 * 衰减起算时刻为拆分时刻；父批未登记效价时效价字段与 modelVersion 为 null。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt,
        Integer modelVersion
) {

    /**
     * 拆分产生的子批概要；效价字段在未登记效价的父批下为 null。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt,
            BigDecimal quantity,
            BigDecimal initialPotency,
            BigDecimal minReleasePotency,
            BigDecimal decayRatePerDay,
            Instant potencyEpochAt
    ) {
    }
}
