package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合批结果响应。targetBatchKey 为保留的目标容器；sources 为各来源扣减明细；
 * targetQuantityAfter 为合批后目标库存余额；mergedAt 为 UTC instant。
 */
public record MergeResponse(
        String allergenKey,
        String targetBatchKey,
        int targetComponentVersion,
        BigDecimal targetQuantityBefore,
        BigDecimal targetQuantityAfter,
        List<MergedSource> sources,
        Instant mergedAt
) {

    /**
     * 单个来源合批明细：来源扣减后余额归零，状态置为 MERGED。
     */
    public record MergedSource(
            String batchKey,
            int componentVersion,
            BigDecimal quantity,
            BigDecimal balanceAfter
    ) {
    }
}
