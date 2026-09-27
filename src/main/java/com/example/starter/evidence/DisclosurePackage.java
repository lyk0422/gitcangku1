package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 披露包版本实体，对应 disclosure_package 表。每个案件的包版本严格递增，版本行不可变：
 * 增删证物生成新版本，不覆盖旧版本；状态仅允许 未交付→已交付 / 未交付→已撤销。
 *
 * @param id             主键
 * @param caseKey        所属案件键
 * @param packageVersion 案件内包版本号，从 1 开始严格递增
 * @param asOfUtc        截至 UTC 时刻，证物历史状态均按该时刻重放判定
 * @param status         版本状态：GENERATED 未交付 / DELIVERED 已交付 / REVOKED 已撤销
 * @param packageKey     包指纹：案件 + 规范化证物集合 + 截至时刻 + 版本 + 操作者的 SHA-256，同键重放
 * @param actorId        生成该版本的操作人
 * @param frozenSummary  生成摘要 JSON（逐件固化快照的汇总），生成后不随后续业务变化
 * @param itemCount      版本内证物件数
 * @param deliverReason  交付备注；null 表示未交付
 * @param deliveredBy    交付操作人；null 表示未交付
 * @param deliveredAt    交付时间（Asia/Shanghai）；null 表示未交付
 * @param revokeReason   撤销原因；null 表示未撤销
 * @param revokedBy      撤销操作人；null 表示未撤销
 * @param revokedAt      撤销时间（Asia/Shanghai）；null 表示未撤销
 * @param createdAt      版本生成时间（Asia/Shanghai）
 */
public record DisclosurePackage(
        Long id,
        String caseKey,
        int packageVersion,
        LocalDateTime asOfUtc,
        PackageStatus status,
        String packageKey,
        String actorId,
        String frozenSummary,
        int itemCount,
        String deliverReason,
        String deliveredBy,
        LocalDateTime deliveredAt,
        String revokeReason,
        String revokedBy,
        LocalDateTime revokedAt,
        LocalDateTime createdAt) {
}
