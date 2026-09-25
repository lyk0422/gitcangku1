package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.PathBlockedRecord;

/**
 * PATH_BLOCKED 拦截记录视图。
 */
public record PathBlockedView(long id, long releaseId, String deviceId, String currentVersion,
                              String requiredVersion, String targetVersion, String blockedAtUtc) {

    public static PathBlockedView of(PathBlockedRecord record) {
        return new PathBlockedView(record.id(), record.releaseId(), record.deviceId(),
                record.currentVersion(), record.requiredVersion(), record.targetVersion(),
                record.blockedAtUtc());
    }
}
