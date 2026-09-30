package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.VersionFreeze;

/**
 * 版本冻结区间视图。恢复后 approvedBy/restoredAtUtc 有值，frozenBy/frozenReason/frozenAtUtc 原样保留。
 */
public record FreezeView(long freezeId, String version, String status, String frozenBy, String frozenReason,
                         String frozenAtUtc, String approvedBy, String restoredAtUtc) {

    public static FreezeView of(VersionFreeze freeze) {
        return new FreezeView(freeze.id(), freeze.versionKey(), freeze.status().name(), freeze.frozenBy(),
                freeze.frozenReason(), freeze.frozenAtUtc(), freeze.approvedBy(), freeze.restoredAtUtc());
    }
}
