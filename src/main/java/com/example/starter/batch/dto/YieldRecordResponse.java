package com.example.starter.batch.dto;

import java.time.Instant;

/**
 * 单批次产率视图。inputQty/outputQty 为保留原始数值的十进制字符串（最多三位小数）；
 * yieldRate 为产出/投入按四位小数展示的字符串，仅用于展示，不回写存储。
 */
public record YieldRecordResponse(
        String batchKey,
        String inputQty,
        String outputQty,
        String yieldRate,
        long version,
        String operator,
        Instant createdAt,
        Instant updatedAt
) {
}
