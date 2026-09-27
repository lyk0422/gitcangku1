package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TaskAttempt;

import java.util.List;

/**
 * 尝试代次历史视图：固化代次建立时的发布版本、聚合摘要与判定结果；旧代次失败记录保留。
 */
public record AttemptView(int attemptNo, String toVersion, String expectedAggregate,
                          int receivedCount, int requiredCount,
                          String verdict, String verdictReason, String actualAggregate,
                          String decidedAtUtc, String createdAtUtc,
                          List<ReceiptView> receipts) {

    public static AttemptView of(TaskAttempt attempt, List<ReceiptView> receipts) {
        return new AttemptView(attempt.attemptNo(), attempt.toVersion(), attempt.expectedAggregate(),
                attempt.receivedCount(), attempt.requiredCount(),
                attempt.verdict(), attempt.verdictReason(), attempt.actualAggregate(),
                attempt.decidedAtUtc(), attempt.createdAtUtc(), receipts);
    }
}
