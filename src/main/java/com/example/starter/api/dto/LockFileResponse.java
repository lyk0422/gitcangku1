package com.example.starter.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列，optionalDependencies 按
 * “来源名称、依赖名称”字典序排列。
 *
 * @param targetPlatform        锁定时固化的目标平台；迁移前的旧锁文件为 null（查询结构保持兼容）
 * @param optionalDependencies  每条可选依赖的 included/skipped 判定结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        String targetPlatform,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<OptionalDependencyResponse> optionalDependencies) {
}
