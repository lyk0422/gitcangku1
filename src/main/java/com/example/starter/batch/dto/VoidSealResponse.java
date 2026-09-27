package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 封箱作废响应：返回被作废的封箱版本与作废后批次包装累计实际值。
 *
 * @param version        被作废的封箱版本
 * @param sealedQuantity 作废后全部活跃封箱数量之和（已释放该封箱数量）
 * @param usedLabelCount 作废后已用（活跃占用）标签数（已释放该标签）
 */
public record VoidSealResponse(
        String batchKey,
        String sealKey,
        int version,
        String reason,
        long sealedQuantity,
        long usedLabelCount,
        Instant voidedAt
) {
}
