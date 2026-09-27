package com.example.starter.disclosure.dto;

import com.example.starter.disclosure.DisclosurePackageStatus;

import java.time.LocalDateTime;

/**
 * 披露包视图。
 *
 * @param packageKey     包指纹
 * @param caseKey        所属案件键
 * @param packageVersion 案件内包版本号
 * @param basedOnVersion 修订基于的版本；null 表示首版
 * @param cutoffAt       披露截至时刻（UTC）
 * @param status         包状态
 * @param actorId        建包操作者
 * @param evidenceCount  包内证物数量
 * @param createdAt      生成时间（Asia/Shanghai）
 * @param deliveredAt    交付时间；null 未交付
 * @param deliveredBy    交付操作者；null 未交付
 * @param revokeReason   撤销原因；null 未撤销
 * @param revokedAt      撤销时间；null 未撤销
 * @param revokedBy      撤销操作者；null 未撤销
 */
public record PackageView(
        String packageKey,
        String caseKey,
        int packageVersion,
        Integer basedOnVersion,
        LocalDateTime cutoffAt,
        DisclosurePackageStatus status,
        String actorId,
        int evidenceCount,
        LocalDateTime createdAt,
        LocalDateTime deliveredAt,
        String deliveredBy,
        String revokeReason,
        LocalDateTime revokedAt,
        String revokedBy) {
}
