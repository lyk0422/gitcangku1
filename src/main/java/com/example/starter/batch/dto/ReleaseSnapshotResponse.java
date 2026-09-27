package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 放行快照（只读固化证据）。放行（第二个有效批准）随事务写入，之后任何作废申请不得改写：
 * 固化计划数量、实际封箱数量、号段内已用标签数、已用标签规范排序摘要（labelSummary，
 * 标签号按数值升序、英文逗号拼接）以及每个封箱版本（records，含已作废的历史版本，
 * 按封箱版本号即提交顺序升序；作废版本仅作证据，实际数量以 packedQuantity 为准）。
 * createdAt 为 UTC 固化时间。
 */
public record ReleaseSnapshotResponse(
        String batchKey,
        int plannedQuantity,
        int packedQuantity,
        int usedLabelCount,
        String labelSummary,
        List<PackResponse.Entry> records,
        Instant createdAt
) {
}
