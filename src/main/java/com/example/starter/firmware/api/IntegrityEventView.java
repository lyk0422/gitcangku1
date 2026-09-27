package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.IntegrityEvent;

/**
 * 完整性事件视图：固化判定时刻的发布版本、分片摘要、聚合结果与失败原因（只读）。
 */
public record IntegrityEventView(long eventId, long taskId, int attemptNo, long releaseId,
                                 String eventType, Integer chunkIndex,
                                 String expectedDigest, String receivedDigest,
                                 Integer chunkCount, String aggregateDigest, String packageDigest,
                                 String reason, String detail, String occurredAtUtc) {

    public static IntegrityEventView of(IntegrityEvent event) {
        return new IntegrityEventView(event.id(), event.taskId(), event.attemptNo(), event.releaseId(),
                event.eventType().name(), event.chunkIndex(), event.expectedDigest(), event.receivedDigest(),
                event.chunkCount(), event.aggregateDigest(), event.packageDigest(),
                event.reason() == null ? null : event.reason().name(),
                event.detail(), event.occurredAtUtc());
    }
}
