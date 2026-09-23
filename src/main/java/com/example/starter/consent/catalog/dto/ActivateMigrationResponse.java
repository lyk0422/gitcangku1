package com.example.starter.consent.catalog.dto;

import java.util.List;

/**
 * 迁移激活成功响应：返回新目录代次、授权拆分与数据改绑统计。
 *
 * @param migrationKey       迁移键
 * @param catalogGeneration  新发布的目录代次
 * @param sourcePurpose      被拆分旧用途代码（状态变为 SPLIT）
 * @param newPurposes        新用途代码列表（稳定排序）
 * @param activeGrantCount   拆分的有效授权数量（每个授权按范围拆为多个新授权）
 * @param newGrantCount      实际新建的新用途授权数量
 * @param reboundRecordCount 一次性改绑到新用途的数据记录数量
 * @param unmappedCount      保留历史旧用途的 UNMAPPED 记录数量
 * @param retainedCount      撤回隔离、保留旧用途的记录数量
 * @param effectiveFrom      生效窗口起点（UTC，左闭）
 * @param effectiveTo        生效窗口终点（UTC，右开）
 */
public record ActivateMigrationResponse(String migrationKey, int catalogGeneration, String sourcePurpose,
                                        List<String> newPurposes, int activeGrantCount, int newGrantCount,
                                        int reboundRecordCount, int unmappedCount, int retainedCount,
                                        java.time.Instant effectiveFrom, java.time.Instant effectiveTo) {
}
