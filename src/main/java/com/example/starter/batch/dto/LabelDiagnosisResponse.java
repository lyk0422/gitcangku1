package com.example.starter.batch.dto;

import java.util.List;

/**
 * 标签差异诊断（只读，不改变状态，也不修复数据）。
 *
 * <ul>
 *   <li>reconcilable：当前核销是否满足放行条件——计划已登记、有效封箱数量之和恰好等于计划数量、
 *       号段内已用标签数等于有效封箱数（无缺失/重复/越界，重复在唯一约束下不可能出现）。</li>
 *   <li>plannedQuantity/packedQuantity/usedLabelCount/activeRecordCount 为实际值；
 *       quantityDifference = plannedQuantity - packedQuantity 为差额（0 表示恰好完成）；
 *       labelCountDifference = usedLabelCount - activeRecordCount（正常恒为 0）。</li>
 *   <li>outOfRangeLabels：当前有效但越出号段的标签（理论上不会出现，用于发现历史数据差异）；
 *       duplicateLabels 恒为空，重复由数据库唯一约束保证。</li>
 *   <li>missingLabels：号段内未用标签，最多返回前 1000 个；missingLabelsTruncated=true 表示被截断。</li>
 *   <li>releaseSnapshot：放行后固化的快照；放行批次发现当前状态与快照不一致时
 *       differences 列出差异项，只能按既有召回流程处置。</li>
 * </ul>
 */
public record LabelDiagnosisResponse(
        String batchKey,
        boolean planRegistered,
        boolean reconcilable,
        int plannedQuantity,
        int packedQuantity,
        int usedLabelCount,
        int activeRecordCount,
        int quantityDifference,
        int labelCountDifference,
        List<Long> outOfRangeLabels,
        List<Long> missingLabels,
        boolean missingLabelsTruncated,
        List<String> differences,
        ReleaseSnapshotResponse releaseSnapshot
) {
}
