package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

/**
 * 查询快照历史摘要：按生成顺序列出快照，供历史查询使用。
 *
 * @param snapshotId     查询快照标识
 * @param createdAt      快照生成时间（UTC）
 * @param allowedRegions 本次查询允许的区域集合（字典序）
 * @param requestedCount 请求查询的对象数（要求值）
 * @param itemCount      快照固化的对象数（实际值）
 */
public record SnapshotSummary(
        String snapshotId,
        Instant createdAt,
        List<String> allowedRegions,
        int requestedCount,
        int itemCount) {
}
