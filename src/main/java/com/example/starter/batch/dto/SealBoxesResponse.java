package com.example.starter.batch.dto;

import java.util.List;

/**
 * 一次批量封箱的响应：返回本次写入的封箱版本及封箱后的实际累计值与差额。
 *
 * @param sealedQuantity    封箱后全部活跃封箱数量之和（实际值）
 * @param plannedQuantity   计划数量（要求值）
 * @param usedLabelCount    封箱后已用（活跃占用）标签数
 * @param sealCount         封箱后活跃封箱条数（应与 usedLabelCount 相等）
 * @param remainingQuantity 距计划数量的差额 = plannedQuantity - sealedQuantity（非负）
 * @param seals             本次提交写入的封箱明细（状态均为 SEALED）
 */
public record SealBoxesResponse(
        String batchKey,
        long sealedQuantity,
        long plannedQuantity,
        long usedLabelCount,
        long sealCount,
        long remainingQuantity,
        List<SealEventResponse> seals
) {
}
