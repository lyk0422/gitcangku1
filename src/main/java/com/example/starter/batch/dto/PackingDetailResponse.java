package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次包装/标签核销明细（只读，不改变状态）。
 *
 * <ul>
 *   <li>plannedQuantity：计划数量（件）；packedQuantity：ACTIVE 封箱数量之和（实际值）；
 *       remainingQuantity：差额 = plannedQuantity - packedQuantity（非负为还需封箱数量）。</li>
 *   <li>usedLabelCount：号段内已用标签数 = ACTIVE 封箱记录数；
 *       labelRangeSize：号段长度；missingLabelCount：号段内未用标签数。</li>
 *   <li>records：全部封箱版本（含已作废），按封箱版本号（提交顺序）升序，历史不被作废或放行改写。</li>
 *   <li>未登记包装计划时 plan 为 null，各数量字段为 0。</li>
 * </ul>
 */
public record PackingDetailResponse(
        String batchKey,
        PackingPlanResponse plan,
        int plannedQuantity,
        int packedQuantity,
        int usedLabelCount,
        int activeRecordCount,
        int voidedRecordCount,
        long labelRangeSize,
        long missingLabelCount,
        int remainingQuantity,
        List<PackingRecordResponse> records,
        ReleaseSnapshotResponse snapshot
) {
}
