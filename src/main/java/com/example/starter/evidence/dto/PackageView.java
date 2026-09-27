package com.example.starter.evidence.dto;

import com.example.starter.evidence.DisclosurePackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包内容视图：包头 + 按规范排序的冻结明细。
 *
 * @param packageKey   披露包业务键
 * @param caseKey      所属案件键
 * @param revision     包版本（修订号）
 * @param asOf         截至时刻（UTC）
 * @param status       包状态：FROZEN / DELIVERED / REVOKED
 * @param operatorId   建包操作者
 * @param fingerprint  包指纹（案件+规范化证物+截至时刻+版本+操作者 的 SHA-256）
 * @param itemCount    包内证物件数
 * @param summary      生成摘要
 * @param deliveredBy  交付操作人；NULL 表示未交付
 * @param deliveredAt  交付时刻（UTC）；NULL 表示未交付
 * @param revokedBy    撤销操作人；NULL 表示未撤销
 * @param revokedAt    撤销时刻（UTC）；NULL 表示未撤销
 * @param revokeReason 撤销原因；NULL 表示未撤销
 * @param createdAt    建包时间（UTC）
 * @param updatedAt    最近一次批量增删或状态变更时间（UTC）
 * @param items        冻结证物明细（按规范排序）
 */
public record PackageView(
        String packageKey,
        String caseKey,
        int revision,
        LocalDateTime asOf,
        DisclosurePackageStatus status,
        String operatorId,
        String fingerprint,
        int itemCount,
        String summary,
        String deliveredBy,
        LocalDateTime deliveredAt,
        String revokedBy,
        LocalDateTime revokedAt,
        String revokeReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PackageItemView> items) {
}
