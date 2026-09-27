package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant 即固化计算时刻。
 * 每个子批以父批在 splitAt 的当前效价为初始效价建立效价模型 v1（锚点 splitAt），
 * 衰减率与最低放行阈值继承父批当前模型版本；父批未登记数量/效价时 potency 为 null。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要；potency 为该子批固化的效价模型 v1，未登记效价时为 null。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            BigDecimal quantity,
            PotencyView potency,
            Instant createdAt
    ) {
    }
}
