package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照响应：批次 ID、发布时间与每张锁定图的固化结果。
 */
public record ReleaseSnapshotResponse(
        long releaseId,
        Instant createdAt,
        List<ReleasedItem> items) {

    /**
     * 批次内单张锁定图的固化结果。
     */
    public record ReleasedItem(
            long lockFileId,
            String rootName,
            int rootVersion,
            List<String> regions,
            List<ReleasedEntry> entries) {
    }

    /**
     * 快照中单个制品的许可证与告知文本固化信息。
     */
    public record ReleasedEntry(
            String name,
            int version,
            boolean direct,
            String licenseId,
            String noticeKey,
            Integer noticeVersion,
            List<String> noticeRegions) {
    }
}
