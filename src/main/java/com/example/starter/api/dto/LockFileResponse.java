package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列，edges 为锁定时冻结的依赖边。
 *
 * @param budgetBytes     锁定时声明的体积预算，单位字节
 * @param totalSizeBytes  锁定时冻结的选中制品体积精确总和（同名只计一次），单位字节
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        long budgetBytes,
        long totalSizeBytes,
        Instant createdAt,
        List<LockEntryResponse> entries,
        List<LockEdgeResponse> edges) {
}
