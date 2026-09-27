package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskAttempt;

/**
 * 任务尝试代次视图（只读）。
 */
public record AttemptView(int attemptNo, String status, String createdAtUtc, String finalizedAtUtc) {

    public static AttemptView of(TaskAttempt attempt) {
        return new AttemptView(attempt.attemptNo(), attempt.status().name(),
                attempt.createdAtUtc(), attempt.finalizedAtUtc());
    }
}
