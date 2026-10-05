package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 多根联合锁文件视图：roots 与 entries 均按名称升序排列。
 */
public record MultiLockFileResponse(
        long id,
        List<MultiLockRoot> roots,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries) {
}
