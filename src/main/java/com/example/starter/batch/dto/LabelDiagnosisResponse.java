package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签差异诊断（只读）：返回当前核销状态、放行快照及二者差异。
 *
 * <p>未放行批次 snapshot 为 null，snapshotDiff 为 null；已放行批次返回固化快照与当前生效占用对比，
 * 差异（如放行后作废导致的标签/数量缺口）只能按既有召回流程处置，本查询不改变任何状态。
 *
 * @param labelSealCountDifference 生效标签数与生效封箱条数之差（恒为 0，非 0 即数据不一致）
 * @param snapshot                 放行快照；未放行或放行时未固化（历史批次）为 null
 * @param snapshotDiff             当前状态相对放行快照的差异；snapshot 为 null 时为 null
 */
public record LabelDiagnosisResponse(
        String batchKey,
        String batchStatus,
        boolean planRegistered,
        int plannedQuantity,
        long labelRangeStart,
        long labelRangeEnd,
        int sealedQuantity,
        int remainingQuantity,
        int activeLabelCount,
        int activeSealCount,
        int labelSealCountDifference,
        boolean reconciled,
        ReleaseSnapshotResponse snapshot,
        SnapshotDiff snapshotDiff
) {
    /**
     * 当前生效状态相对放行快照的差异；所有列表均按标签号升序。
     *
     * @param labelsOnlyInSnapshot   快照中有、当前已不再生效占用的标签（如放行后被作废释放）
     * @param labelsOnlyInCurrent    当前生效占用、但快照中没有的标签（正常流程放行后不应新增）
     * @param currentSealedQuantity  当前生效封箱数量合计
     * @param snapshotSealedQuantity 快照固化的封箱数量合计
     * @param sealedQuantityDelta    当前 - 快照的数量差（负数表示放行后数量减少）
     * @param snapshotImmutable      快照是否仍与固化时一致（恒为 true；快照永不被改写）
     */
    public record SnapshotDiff(
            List<Long> labelsOnlyInSnapshot,
            List<Long> labelsOnlyInCurrent,
            int currentSealedQuantity,
            int snapshotSealedQuantity,
            int sealedQuantityDelta,
            boolean snapshotImmutable
    ) {
    }
}
