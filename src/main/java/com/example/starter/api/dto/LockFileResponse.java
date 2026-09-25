package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 *
 * @param policyVersion 锁定时生效的许可证策略版本号；null 表示锁定时该命名空间未配置策略
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Long policyVersion,
        Instant createdAt,
        List<LockEntryResponse> entries) {
}
