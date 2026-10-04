package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant。
 * 父批已绑定配方时，子批按目标质量比例继承各成分质量并绑定同一配方。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要；targetMassGrams 为继承的目标总质量（克），父批未绑定配方时为 null。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt,
            BigDecimal targetMassGrams
    ) {
    }
}