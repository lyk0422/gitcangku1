package com.example.starter.sampling;

import java.util.List;
import java.util.Map;

/**
 * 批量离线合并响应：返回接收/重放/合并计数、冲突簇与各许可最终计数。
 *
 * @param batchId             批量合并标识
 * @param receivedCount       本批次新接收观测数（不含重放）
 * @param replayedCount       重放条目数（已存在键或批内重复键，不重复计数）
 * @param mergedCount         直接合并的观测数（无冲突条目）
 * @param conflictClusterIds  本次产生的冲突簇标识列表
 * @param permitCounts        各命中许可的最终已用计数（许可标识 → 计数）
 */
public record BatchMergeResponse(
        String batchId,
        int receivedCount,
        int replayedCount,
        int mergedCount,
        List<String> conflictClusterIds,
        Map<String, Integer> permitCounts) {
}
