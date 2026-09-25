package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.PathBlockedRecord;

/**
 * PATH_BLOCKED 判定历史记录视图。
 */
public record PathBlockedRecordView(long recordId, long releaseId, String deviceId, String deviceVersion,
                                    String targetVersion, String nextVersion, String blockedAtUtc) {

    public static PathBlockedRecordView of(PathBlockedRecord record) {
        return new PathBlockedRecordView(record.id(), record.releaseId(), record.deviceId(),
                record.deviceVersion(), record.targetVersion(), record.nextVersion(), record.blockedAtUtc());
    }
}
