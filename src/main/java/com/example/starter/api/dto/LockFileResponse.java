package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 *
 * @param policyVersion 锁定图绑定的来源策略版本；0 表示未绑定来源策略（按旧规则解析）
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        int policyVersion,
        Instant createdAt,
        List<LockEntryResponse> entries) {
}
