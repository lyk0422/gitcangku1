package com.example.starter.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁文件视图：历史查询与锁定成功均返回该结构。
 *
 * @param id                锁文件ID
 * @param rootName          根制品名称
 * @param rootVersion       根制品精确版本
 * @param repositoryVersion 锁定时读到的仓库版本
 * @param items             精确解析结果（含根），按名称排序
 * @param createdAt         创建时间（UTC 时间线）
 */
public record LockFileResponse(
        Long id,
        String rootName,
        Integer rootVersion,
        Long repositoryVersion,
        List<ResolvedItem> items,
        Instant createdAt
) {
}
