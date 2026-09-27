package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行标签快照：批次进入 RELEASED 时固化，此后不可改写。
 *
 * @param plannedQuantity  固化的计划包装数量
 * @param sealedQuantity   固化的实际封箱数量合计
 * @param usedLabels       已用标签号规范排序摘要（升序逗号分隔）
 * @param rangeSummary     已用标签号段压缩摘要（升序，如 1-3,7-8）
 * @param usedLabelCount   已用标签数
 * @param sealVersionCount 固化的封箱版本条数（应等于 usedLabelCount）
 * @param seals            每个封箱版本明细，按标签号升序
 * @param capturedAt       固化时间，UTC instant
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        int plannedQuantity,
        int sealedQuantity,
        String usedLabels,
        String rangeSummary,
        int usedLabelCount,
        int sealVersionCount,
        List<SnapshotSealResponse> seals,
        Instant capturedAt
) {
}
