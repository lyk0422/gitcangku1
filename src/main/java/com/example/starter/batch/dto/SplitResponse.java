package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要。子批继承父批保质分钟，并按自身生产时间重新计算有效期。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt,
            int shelfLifeMinutes,
            Instant baseExpiresAt,
            Instant expiresAt,
            boolean expired
    ) {
    }
}
