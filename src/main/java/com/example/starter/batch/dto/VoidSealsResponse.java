package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量作废响应：列出本次作废条目及释放的标签占用与数量，返回作废后的实际值/要求值/差额。
 *
 * @param voidedSeals        本次作废的封箱记录（含作废原因与时间，按请求顺序）
 * @param releasedQuantity   本次释放的封箱数量合计
 * @param sealedQuantity     作废后生效封箱数量合计（实际值）
 * @param plannedQuantity    计划包装数量（要求值）
 * @param remainingQuantity  距完成计划尚缺数量（差额）
 * @param activeLabelCount   作废后生效占用标签数
 * @param activeSealCount    作废后生效封箱条数
 * @param voidedAt           本次作废时间，UTC instant
 */
public record VoidSealsResponse(
        String batchKey,
        List<SealRecordResponse> voidedSeals,
        int releasedQuantity,
        int sealedQuantity,
        int plannedQuantity,
        int remainingQuantity,
        int activeLabelCount,
        int activeSealCount,
        Instant voidedAt
) {
}
