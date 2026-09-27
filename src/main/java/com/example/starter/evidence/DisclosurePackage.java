package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物披露包实体，对应 disclosure_package 表。包键与冻结内容在建包成功后不可变；
 * 仅允许未交付（FROZEN）撤销；已交付（DELIVERED）只能创建更高修订包。
 *
 * @param id              主键
 * @param packageKey      披露包业务键（案件+规范化证物集+截至时刻+版本+操作者指纹），全局唯一
 * @param caseKey         所属案件键
 * @param cutoffAt        截至 UTC 时刻，历史状态按该时刻重放
 * @param packageVersion  案件内包版本号，从 1 递增
 * @param status          包状态
 * @param operatorId      建包操作者（指纹组成部分）
 * @param evidenceCount   冻结证物件数
 * @param packageDigest   整包生成摘要 SHA-256
 * @param basePackageKey  增删修订时基准包键；直接建包为 null
 * @param deliveredBy     交付操作人；null 未交付
 * @param deliveredAt     交付时间（Asia/Shanghai）；null 未交付
 * @param revokeReason    撤销原因；null 未撤销
 * @param revokedBy       撤销操作人；null 未撤销
 * @param revokedAt       撤销时间（Asia/Shanghai）；null 未撤销
 * @param createdAt       建包时间（Asia/Shanghai）
 * @param updatedAt       最近状态变更时间（Asia/Shanghai）
 */
public record DisclosurePackage(
        Long id,
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
        String revokeReason,
        String revokedBy,
        LocalDateTime revokedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
