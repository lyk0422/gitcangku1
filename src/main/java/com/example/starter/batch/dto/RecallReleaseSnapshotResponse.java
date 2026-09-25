package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 召回解除评审快照：批准时写入，写入后不可变；closureBatches 为批准时最终血缘闭包（规范排序）。
 */
public record RecallReleaseSnapshotResponse(
        String releaseKey,
        String batchKey,
        int recallVersion,
        List<String> closureBatches,
        String correctiveMeasures,
        String approver,
        Instant snapshotAt
) {
}
