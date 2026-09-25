package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单批次产率记录视图。inputQuantity/outputQuantity 为保留的原始数值；
 * yieldRate 为产出/投入按四位小数（HALF_UP）展示的产率；version 为当前记录版本。
 */
public record YieldEntryResponse(
        String batchKey,
        BigDecimal inputQuantity,
        BigDecimal outputQuantity,
        BigDecimal yieldRate,
        long version,
        String operatorId,
        Instant updatedAt
) {
}
