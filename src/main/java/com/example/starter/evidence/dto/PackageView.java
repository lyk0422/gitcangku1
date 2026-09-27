package com.example.starter.evidence.dto;

import com.example.starter.evidence.DisclosurePackageStatus;

import java.time.LocalDateTime;

/**
 * 披露包冻结摘要视图（列表与详情共用）。
 *
 * @param packageKey     披露包业务键（指纹）
 * @param caseKey        案件键
 * @param cutoffAt       截至 UTC 时刻
 * @param packageVersion 案件内包版本号
 * @param status         包状态：FROZEN/DELIVERED/REVOKED
 * @param operatorId     建包操作者
 * @param evidenceCount  冻结证物件数
 * @param packageDigest  整包生成摘要 SHA-256
 * @param basePackageKey 修订基准包键；直接建包为 null
 * @param deliveredBy    交付操作人；null 未交付
 * @param deliveredAt    交付时间（Asia/Shanghai）；null 未交付
 * @param revokedBy      撤销操作人；null 未撤销
 * @param revokeReason   撤销原因；null 未撤销
 * @param revokedAt      撤销时间（Asia/Shanghai）；null 未撤销
 * @param createdAt      建包时间（Asia/Shanghai）
 * @param updatedAt      最近状态变更时间（Asia/Shanghai）
 */
public record PackageView(
        String packageKey,
        String caseKey,
        LocalDateTime cutoffAt,
        int packageVersion,
        DisclosurePackageStatus status,
        String operatorId,
        int evidenceCount,
        String packageDigest,
        String basePackageKey,
        String deliveredBy,
        LocalDateTime deliveredAt,
        String revokedBy,
        String revokeReason,
        LocalDateTime revokedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
