package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 标签核销诊断响应：计划/实际/差额、标签占用计数、放行快照及（已放行批次的）差异诊断。
 * snapshot 在未放行时为 null；discrepancy 在无快照或与快照一致时为 null。
 */
public record ReconciliationResponse(
        String batchKey,
        int plannedQuantity,
        long labelStart,
        long labelEnd,
        int sealedQuantity,
        int remainingQuantity,
        int activeCartonCount,
        int usedLabelCount,
        int voidedCartonCount,
        boolean complete,
        SnapshotView snapshot,
        DiscrepancyView discrepancy
) {
    /**
     * 放行快照视图：放行时固化，后续操作不改写。
     */
    public record SnapshotView(
            int plannedQuantity,
            int sealedQuantity,
            int cartonCount,
            List<Long> labels,
            String labelDigest,
            Map<String, Integer> cartonVersions,
            Instant createdAt
    ) {
    }

    /**
     * 已放行批次标签差异诊断：当前有效标签与放行快照的对比结果。
     * missingLabels 快照有而当前缺失；unexpectedLabels 当前有而快照外新增；
     * quantityDifference 为当前已封数量减去快照数量（正为超出、负为不足）。
     */
    public record DiscrepancyView(
            boolean digestMatch,
            List<Long> missingLabels,
            List<Long> unexpectedLabels,
            int quantityDifference
    ) {
    }
}
