package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物披露包实体，对应 disclosure_package 表。
 * 建包成功后内容（证物集合、截至时刻、指纹）冻结；仅状态与交付/撤销字段随状态机流转。
 *
 * @param id           主键
 * @param packageKey   披露包业务键，全局唯一，同时作为建包幂等键
 * @param caseKey      所属案件键
 * @param revision     包版本（修订号），同一案件内严格递增
 * @param asOf         截至时刻（UTC），包内全部历史状态按该时刻重放判定
 * @param status       包状态：FROZEN / DELIVERED / REVOKED
 * @param operatorId   建包操作者
 * @param fingerprint  包指纹（案件+规范化证物+截至时刻+版本+操作者 的 SHA-256）
 * @param evidenceKeys 规范化证物键列表 JSON（去重、字典序）
 * @param itemCount    包内证物件数
 * @param summary      生成摘要
 * @param deliveredBy  交付操作人；NULL 表示未交付
 * @param deliveredAt  交付时刻（UTC）；NULL 表示未交付
 * @param revokedBy    撤销操作人；NULL 表示未撤销
 * @param revokedAt    撤销时刻（UTC）；NULL 表示未撤销
 * @param revokeReason 撤销原因；NULL 表示未撤销
 * @param createdAt    建包时间（UTC）
 * @param updatedAt    最近一次批量增删或状态变更时间（UTC）
 */
public record DisclosurePackage(
        Long id,
        String packageKey,
        String caseKey,
        int revision,
        LocalDateTime asOf,
        DisclosurePackageStatus status,
        String operatorId,
        String fingerprint,
        String evidenceKeys,
        int itemCount,
        String summary,
        String deliveredBy,
        LocalDateTime deliveredAt,
        String revokedBy,
        LocalDateTime revokedAt,
        String revokeReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
