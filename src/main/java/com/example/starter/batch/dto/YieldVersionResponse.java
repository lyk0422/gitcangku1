package com.example.starter.batch.dto;

import com.example.starter.batch.YieldGateStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 单个产率核算版本结果（提交与历史共用）。
 * 数量与偏差均三位小数；limit 为该版本计算时已冻结的允许上限快照（三位小数）；
 * disposers 为确认该版本的两名不同处置人，未齐两名时不足两名。
 */
public record YieldVersionResponse(
        long version,
        String requestId,
        BigDecimal inputQty,
        BigDecimal goodQty,
        BigDecimal lossQty,
        BigDecimal scrapQty,
        BigDecimal deviation,
        BigDecimal limit,
        boolean withinLimit,
        YieldGateStatus gateStatus,
        List<String> disposers,
        Instant createdAt
) {
}
