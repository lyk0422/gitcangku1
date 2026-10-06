package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 作用域锁文件视图：结构化的作用域树、精确版本与依赖边。
 *
 * @param scopes 全部作用域，根作用域在前，父作用域恒先于子作用域
 * @param edges  全部依赖边
 */
public record ScopedLockFileResponse(
        long id,
        String rootName,
        int rootVersion,
        long repositoryVersion,
        Instant createdAt,
        List<ScopeView> scopes,
        List<EdgeView> edges) {
}
