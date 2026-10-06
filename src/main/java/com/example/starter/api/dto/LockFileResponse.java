package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 历史锁文件视图，entries 按（作用域路径，名称）升序排列。
 *
 * @param mode    锁定模式：FLAT=旧单根扁平锁定；SCOPED=带私有作用域锁定
 * @param entries 冻结的精确版本（含结构化作用域路径）
 * @param edges   冻结的依赖边（按登记声明原样保存）
 */
public record LockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        String mode,
        List<LockEntryResponse> entries,
        List<LockEdgeResponse> edges) {

    /** 旧扁平锁视图：无作用域与边明细。 */
    public LockFileResponse(long id, String rootName, int rootVersion, long repositoryVersion,
                            Instant createdAt, List<LockEntryResponse> entries) {
        this(id, rootName, rootVersion, repositoryVersion, createdAt, "FLAT", entries, List.of());
    }
}
