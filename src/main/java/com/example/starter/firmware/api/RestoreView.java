package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FreezeRestore;

/**
 * 冻结恢复申请与审批审计视图。
 */
public record RestoreView(long restoreId, long freezeId, String requestedBy, String reason,
                          String requestedAtUtc, String status, String reviewedBy, String reviewedAtUtc) {

    public static RestoreView of(FreezeRestore restore) {
        return new RestoreView(restore.id(), restore.freezeId(), restore.requestedBy(), restore.reason(),
                restore.requestedAtUtc(), restore.status().name(), restore.reviewedBy(), restore.reviewedAtUtc());
    }
}
