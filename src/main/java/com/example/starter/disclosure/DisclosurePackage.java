package com.example.starter.disclosure;

import java.time.LocalDateTime;

/**
 * 证物披露包实体，对应 disclosure_package 表。
 * 包生成后 summary_json 与版本信息不可变；仅交付与撤销会补写各自的状态字段，且状态只向前流转。
 *
 * @param id             主键
 * @param packageKey     包指纹（案件+规范化证物集合+截至时刻+版本+操作者的 SHA-256），全局唯一
 * @param caseKey        所属案件键
 * @param packageVersion 案件内单调递增版本号，从 1 开始
 * @param basedOnVersion 增删修订所基于的版本；null 表示首版
 * @param cutoffAt       披露截至时刻（UTC）
 * @param status         包状态
 * @param actorId        建包操作者
 * @param evidenceCount  包内证物数量
 * @param summaryJson    冻结摘要 JSON，生成后不再改写
 * @param createdAt      生成时间（Asia/Shanghai）
 * @param deliveredAt    交付时间；null 表示未交付
 * @param deliveredBy    交付操作者；null 表示未交付
 * @param revokeReason   撤销原因；null 表示未撤销
 * @param revokedAt      撤销时间；null 表示未撤销
 * @param revokedBy      撤销操作者；null 表示未撤销
 */
public record DisclosurePackage(
        Long id,
        String packageKey,
        String caseKey,
        int packageVersion,
        Integer basedOnVersion,
        LocalDateTime cutoffAt,
        DisclosurePackageStatus status,
        String actorId,
        int evidenceCount,
        String summaryJson,
        LocalDateTime createdAt,
        LocalDateTime deliveredAt,
        String deliveredBy,
        String revokeReason,
        LocalDateTime revokedAt,
        String revokedBy) {
}
