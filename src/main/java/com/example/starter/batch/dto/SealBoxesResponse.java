package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批量封箱响应：完整列出本次生效封箱，并返回核销守恒所需的实际值/要求值/差额，调用方无需猜测。
 *
 * @param acceptedSeals     本次新生效的封箱记录（按请求顺序）
 * @param sealedQuantity    本次提交后该批次生效封箱数量合计（实际值）
 * @param plannedQuantity   计划包装数量（要求值）
 * @param remainingQuantity 距完成计划尚缺数量 = plannedQuantity - sealedQuantity（差额，0 表示数量已封足）
 * @param activeLabelCount  号段内当前已用（生效占用）标签数
 * @param activeSealCount   当前生效封箱条数；与 activeLabelCount 必须相等
 * @param labelRangeStart   号段起点（含）
 * @param labelRangeEnd     号段终点（含）
 * @param rangeLabelCount   号段标签总数 = end - start + 1
 * @param remainingLabels   号段内未占用标签数 = rangeLabelCount - activeLabelCount
 * @param reconciled        是否已满足放行核销条件（数量封足且标签数=封箱数）
 * @param sealedAt          本次封箱时间，UTC instant
 */
public record SealBoxesResponse(
        String batchKey,
        List<SealRecordResponse> acceptedSeals,
        int sealedQuantity,
        int plannedQuantity,
        int remainingQuantity,
        int activeLabelCount,
        int activeSealCount,
        long labelRangeStart,
        long labelRangeEnd,
        long rangeLabelCount,
        long remainingLabels,
        boolean reconciled,
        Instant sealedAt
) {
}
