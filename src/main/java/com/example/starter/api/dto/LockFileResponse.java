package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序，optionalDependencies 按
 * “来源名称、依赖名称”字典序排列。
 *
 * @param targetPlatform       锁定时固化的目标平台（os/arch）；平台特性上线前的旧锁文件为 null
 * @param entries              精确制品版本集合（必选 + 成功加入的可选），按名称升序
 * @param optionalDependencies 每条可选依赖的 included/skipped 评估结果
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        String targetPlatform,
        long repositoryVersion,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<OptionalDependencyView> optionalDependencies) {
}
