package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图：entries 按名称升序，edges 为实际保留边，
 * exclusions 为路径排除处置记录；生成后不改写。
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<LockEdgeResponse> edges,
        List<LockExclusionResponse> exclusions) {
}
