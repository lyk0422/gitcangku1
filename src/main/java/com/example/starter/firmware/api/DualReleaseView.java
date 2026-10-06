package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.DualRelease;

/**
 * 双分区发布单视图。
 */
public record DualReleaseView(long releaseId, int version, String model, String targetVersion,
                              String status, int committedCount, int rolledBackCount) {

    public static DualReleaseView of(DualRelease release) {
        return new DualReleaseView(release.id(), release.version(), release.model(),
                release.targetVersion(), release.status().name(),
                release.committedCount(), release.rolledBackCount());
    }
}
