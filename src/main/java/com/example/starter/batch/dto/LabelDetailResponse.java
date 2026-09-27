package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签核销当前明细：计划、守恒汇总与当前生效封箱（不含已作废历史）。
 * 纯只读投影，不含读取时间戳，同一状态下重复读取结果完全一致，不改变状态。
 *
 * @param planRegistered    是否已登记标签计划
 * @param plannedQuantity   计划包装数量（要求值）；未登记计划为 0
 * @param sealedQuantity    生效封箱数量合计（实际值）
 * @param remainingQuantity 距完成计划尚缺数量（差额）；未登记计划为 0
 * @param activeLabelCount  号段内生效占用标签数
 * @param activeSealCount   生效封箱条数
 * @param labelRangeStart   号段起点（含）；未登记计划为 0
 * @param labelRangeEnd     号段终点（含）；未登记计划为 0
 * @param rangeLabelCount   号段标签总数
 * @param remainingLabels   号段内未占用标签数
 * @param reconciled        是否已满足放行核销条件（数量封足且标签数=封箱数且计划已登记）
 * @param activeSeals       当前生效封箱，按标签号升序
 */
public record LabelDetailResponse(
        String batchKey,
        String batchStatus,
        boolean planRegistered,
        int plannedQuantity,
        int sealedQuantity,
        int remainingQuantity,
        int activeLabelCount,
        int activeSealCount,
        long labelRangeStart,
        long labelRangeEnd,
        long rangeLabelCount,
        long remainingLabels,
        boolean reconciled,
        List<SealRecordResponse> activeSeals
) {
}
