package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序，optionalDependencies 按“来源名称、依赖名称”升序。
 *
 * @param targetPlatform       固化的目标平台；历史锁文件可能为 null
 * @param optionalDependencies 每条可选依赖的 included/skipped 结果；历史锁文件为空列表
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        String targetPlatform,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<OptionalResultView> optionalDependencies) {
}
