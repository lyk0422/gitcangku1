package com.example.starter.batch.dto;

import com.example.starter.batch.YieldGateStatus;

import java.math.BigDecimal;

/**
 * 批次产率当前查询结果：最新核算版本的四类数量、偏差、冻结上限与门禁处置状态。
 * 尚未提交核算时 version 为 null、gateStatus 为 NONE，四类数量与偏差均为 null。
 */
public record YieldCurrentResponse(
        String batchKey,
        Long version,
        String requestId,
        BigDecimal inputQty,
        BigDecimal goodQty,
        BigDecimal lossQty,
        BigDecimal scrapQty,
        BigDecimal deviation,
        BigDecimal limit,
        YieldGateStatus gateStatus
) {
}
