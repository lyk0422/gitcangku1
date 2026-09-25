package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定图发布快照视图：发布成功时刻对锁定图精确条目复制的不可变快照。
 *
 * <p>豁免撤销或到期只影响后续发布，不会改写任何已成功生成的发布快照。
 */
public record PublishSnapshotResponse(
        long id,
        long lockFileId,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant publishedAt,
        List<LockEntryResponse> entries) {
}
