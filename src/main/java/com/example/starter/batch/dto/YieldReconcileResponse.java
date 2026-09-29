package com.example.starter.batch.dto;

import com.example.starter.batch.YieldStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 产率核算版本响应（提交返回与当前/历史查询共用）。
 * 四类数量、偏差与冻结上限均为三位小数；toleranceLimit 为该版本计算时冻结的上限，
 * 不随后续配置调整回写；dispositionHandlers 为确认该版本的处置人（至多两名不同人员）；
 * releasedAt 非空表示该版本核算已放行。
 */
public record YieldReconcileResponse(
        String batchKey,
        int version,
        String requestId,
        BigDecimal inputQuantity,
        BigDecimal qualifiedQuantity,
        BigDecimal lossQuantity,
        BigDecimal scrapQuantity,
        BigDecimal deviation,
        BigDecimal toleranceLimit,
        YieldStatus status,
        List<YieldDispositionResponse> dispositionHandlers,
        boolean released,
        Instant releasedAt,
        Instant submittedAt
) {
}
