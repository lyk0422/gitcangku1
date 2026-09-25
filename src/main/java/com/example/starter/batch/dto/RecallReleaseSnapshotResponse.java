package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;
import java.util.List;

/**
 * 解除评审快照响应：批准时按最终血缘闭包写入，写入后不可变。
 */
public record RecallReleaseSnapshotResponse(
        String releaseKey,
        String batchKey,
        int recallVersion,
        String correctiveAction,
        String approver,
        List<String> closureBatches,
        List<SnapshotEntry> entries,
        Instant createdAt
) {

    /**
     * 快照中单个批次的核对明细：批准时批次状态、必做检验项与已合格复检项。
     */
    public record SnapshotEntry(
            String batchKey,
            BatchStatus status,
            List<String> requiredItems,
            List<String> passedItems
    ) {
    }
}
