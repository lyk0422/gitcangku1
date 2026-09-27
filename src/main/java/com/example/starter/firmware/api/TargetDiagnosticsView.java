package com.example.starter.firmware.api;

/**
 * 发布单目标投放诊断视图（只读）。数量字段均为实际值；
 * notPulledCount 为目标快照数与已下发任务数的差额（targetCount - pulledCount）。
 */
public record TargetDiagnosticsView(long releaseId, int targetCount, int pulledCount,
                                    int notPulledCount, int pendingCount, int successCount,
                                    int failedCount, int cancelledCount) {
}
