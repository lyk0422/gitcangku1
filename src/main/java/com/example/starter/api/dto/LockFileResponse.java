package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries) {
}
