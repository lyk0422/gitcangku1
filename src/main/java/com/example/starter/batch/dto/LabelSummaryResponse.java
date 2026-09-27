package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 标签核销汇总（历史视图）。plan 未登记时为 null；usedLabels 为当前有效标签按数值升序的
 * 规范排序；snapshot 为放行时固化的快照，未放行或无计划时为 null，固化后不改写。
 */
public record LabelSummaryResponse(
        String batchKey,
        LabelPlanResponse plan,
        int sealedQuantity,
        int plannedQuantity,
        int remainingQuantity,
        int activeCaseCount,
        List<Long> usedLabels,
        SnapshotView snapshot
) {

    /**
     * 放行标签快照视图：固化计划数量、实际封箱数量、已用标签规范排序摘要与每个封箱版本。
     */
    public record SnapshotView(
            int plannedQuantity,
            int sealedQuantity,
            int caseCount,
            String labelDigest,
            String caseVersions,
            Instant createdAt
    ) {
    }
}
