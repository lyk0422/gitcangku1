package com.example.starter.consent.migration.dto;

import java.time.Instant;
import java.util.List;

/**
 * 迁移激活成功响应（同时作为 requestId 重放快照）。
 *
 * @param migrationKey    迁移键
 * @param catalogVersion  旧目录代次
 * @param catalogGeneration 新发布的目录代次
 * @param sourcePurpose   被拆分旧用途
 * @param effectiveStart  生效窗口起点（UTC，左闭）
 * @param effectiveEnd    生效窗口终点（UTC，右开）
 * @param targetPurposes  新用途代码（稳定排序）
 * @param migratedGrants  拆分为 MIGRATED 的有效授权数
 * @param newGrants       为有效授权签发的新用途授权数
 * @param reboundRecords  一次性改绑到新用途的数据记录数
 * @param unmappedRecords 保留旧用途的 UNMAPPED 活跃记录数
 * @param isolatedRecords 已撤回授权隔离、保留历史旧用途的记录数
 */
public record MigrationActivateResponse(String migrationKey,
                                        long catalogVersion,
                                        long catalogGeneration,
                                        String sourcePurpose,
                                        Instant effectiveStart,
                                        Instant effectiveEnd,
                                        List<String> targetPurposes,
                                        int migratedGrants,
                                        int newGrants,
                                        int reboundRecords,
                                        int unmappedRecords,
                                        int isolatedRecords) {
}
