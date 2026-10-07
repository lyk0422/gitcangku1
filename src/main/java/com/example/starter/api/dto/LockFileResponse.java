package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按名称升序排列。
 *
 * @param budgetBytes     锁定请求声明的体积预算，单位字节，非负
 * @param totalSizeBytes 选中制品去重后的精确总字节数（同名只计一次，根计入）
 * @param operator        发起锁定写入的操作者标识，非空
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        long budgetBytes,
        long totalSizeBytes,
        String operator,
        Instant createdAt,
        List<LockEntryResponse> entries) {
}
