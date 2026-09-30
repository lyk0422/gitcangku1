package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.FreezeRecord;

/**
 * 版本冻结记录视图。FROZEN 时恢复相关字段为 null；RESUMED 时冻结区间字段全部保留。
 */
public record FreezeView(long freezeId, String version, String status, String createdBy, String reason,
                        String frozenAtUtc, String resumeRequestedBy, String resumeReason,
                        String resumeRequestedAtUtc, String approvedBy, String resumedAtUtc) {

    public static FreezeView of(FreezeRecord record) {
        return new FreezeView(record.id(), record.version(), record.status().name(), record.createdBy(),
                record.reason(), record.frozenAtUtc(), record.resumeRequestedBy(), record.resumeReason(),
                record.resumeRequestedAtUtc(), record.approvedBy(), record.resumedAtUtc());
    }
}
