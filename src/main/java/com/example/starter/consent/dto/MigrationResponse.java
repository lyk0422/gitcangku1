package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 驻留迁移响应：返回源/目标代次与区域，以及要求值与实际值数量。
 *
 * @param subjectKey       主体标识（合成字符串）
 * @param purpose          用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param sourceEpoch      源授权代次（已撤回）
 * @param targetEpoch      目标授权代次（新建）
 * @param sourceRegionCode 源驻留区域代码；NULL 表示源代次未登记区域
 * @param targetRegionCode 目标驻留区域代码
 * @param requestedCount   请求迁移的记录数（要求值）
 * @param copiedCount      实际原子复制的记录数（实际值）
 * @param migratedAt       迁移提交时间（UTC）
 */
public record MigrationResponse(
        String subjectKey,
        Purpose purpose,
        int sourceEpoch,
        int targetEpoch,
        String sourceRegionCode,
        String targetRegionCode,
        int requestedCount,
        int copiedCount,
        Instant migratedAt) {
}
