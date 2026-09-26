package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 标签核销诊断：计划/实际/差额、当前标签摘要与放行快照比对结果。
 * snapshot 为 null 表示批次未放行、无快照；digestMatchesSnapshot 在无快照时为 null；
 * discrepancies 为快照与当前活跃封箱的差异诊断（正常为空），只读，不改变状态。
 */
public record LabelDiagnosticsResponse(
        String batchKey,
        String batchStatus,
        Integer plannedQuantity,
        int sealedQuantity,
        Integer remainingQuantity,
        int usedLabelCount,
        int activeSealCount,
        boolean labelCountMatchesSealCount,
        boolean released,
        String currentLabelDigest,
        SnapshotView snapshot,
        Boolean digestMatchesSnapshot,
        List<String> discrepancies
) {
    /**
     * 放行快照视图：固化时的计划数量、实际封箱数量、已用标签摘要与每个封箱版本。
     */
    public record SnapshotView(
            int plannedQuantity,
            int sealedQuantity,
            int labelCount,
            String labelDigest,
            List<SnapshotSeal> seals,
            Instant createdAt
    ) {
    }

    /**
     * 快照中的单个封箱：sealKey、标签号、数量与放行时的版本。
     */
    public record SnapshotSeal(
            String sealKey,
            long labelNo,
            int quantity,
            int version
    ) {
    }
}
