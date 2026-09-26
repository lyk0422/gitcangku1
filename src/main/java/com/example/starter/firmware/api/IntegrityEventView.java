package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.IntegrityEvent;

/**
 * 完整性判定事件视图。actualFullDigest 在集合不完整无法聚合时为 null。
 */
public record IntegrityEventView(long taskId, int attemptNo, long releaseId, int releaseVersion,
                                 String result, String reason, int requiredShardCount,
                                 int receivedShardCount, int missingShardCount,
                                 String missingShards, String duplicateShards, String mismatchedShards,
                                 String expectedFullDigest, String actualFullDigest, String decidedAtUtc) {

    public static IntegrityEventView of(IntegrityEvent event) {
        return new IntegrityEventView(event.taskId(), event.attemptNo(), event.releaseId(),
                event.releaseVersion(), event.result(), event.reason(), event.requiredShardCount(),
                event.receivedShardCount(), event.missingShardCount(), event.missingShards(),
                event.duplicateShards(), event.mismatchedShards(), event.expectedFullDigest(),
                event.actualFullDigest(), event.decidedAtUtc());
    }
}
