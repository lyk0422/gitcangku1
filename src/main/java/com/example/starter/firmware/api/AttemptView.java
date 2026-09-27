package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskAttempt;

/**
 * 尝试代次视图：旧失败代次随重新拉取永久保留。
 */
public record AttemptView(int attemptNo, long releaseId, int releaseVersion, String status,
                          int requiredShards, int receivedShards, String packageDigest,
                          String aggregateDigest, String failureReason,
                          String createdAtUtc, String finalizedAtUtc) {

    public static AttemptView of(TaskAttempt attempt) {
        return new AttemptView(attempt.attemptNo(), attempt.releaseId(), attempt.releaseVersion(),
                attempt.status().name(), attempt.requiredShards(), attempt.receivedShards(),
                attempt.packageDigest(), attempt.aggregateDigest(), attempt.failureReason(),
                attempt.createdAtUtc(), attempt.finalizedAtUtc());
    }
}
