package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 发布快照视图：整批发布的幂等键、目标地区与各锁定图固化内容。
 */
public record PublishResponse(
        long snapshotId,
        String noticeKey,
        List<String> targetRegions,
        Instant createdAt,
        List<PublishLockView> locks) {
}
