package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;

/**
 * 授权代次驻留诊断视图：每个代次的区域、授权版本与记录统计，只读。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          授权代次
 * @param status         状态：ACTIVE 有效 / REVOKED 已撤回
 * @param regionCode     本代登记区域；NULL 表示未登记
 * @param consentVersion 授权版本号
 * @param recordCount    本代记录总数（实际值）
 * @param erasedCount    本代已清除记录数（实际值）
 * @param createdAt      创建时间（UTC）
 * @param revokedAt      撤回时间（UTC），未撤回为 NULL
 */
public record GrantResidencyView(
        String subjectKey,
        Purpose purpose,
        int epoch,
        GrantStatus status,
        String regionCode,
        int consentVersion,
        int recordCount,
        int erasedCount,
        Instant createdAt,
        Instant revokedAt) {
}
