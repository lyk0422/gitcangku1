package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列，deprecationWarnings 按制品、版本升序排列。
 *
 * <p>resolvedAt 与固化的警告均为锁定时刻快照：之后调整/撤销弃用窗口或撤回替代版本均不改写。
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant resolvedAt,
        Instant createdAt,
        List<DeprecationWarningView> deprecationWarnings,
        List<LockEntryResponse> entries) {
}
