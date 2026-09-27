package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant。
 * 父批登记效价模型时，每个子批携带拆分时刻父批当前效价作为初始效价（initialPotency），
 * 未登记时 initialPotency 为 null。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要。initialPotency 为子批效价模型初始效价（父批在拆分时刻的
     * 当前效价，4 位小数 HALF_UP），父批未登记效价模型时为 null。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            Instant createdAt,
            BigDecimal initialPotency
    ) {
    }
}
