package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 解除历史条目：解除记录不可变，同一冻结只会出现一条。
 *
 * @param holdKey     冻结标识
 * @param legalReason 法定事由
 * @param createdBy   创建人（保留角色操作人标识）
 * @param releasedBy  解除人（保留角色操作人标识，不同于创建人）
 * @param releasedAt  解除时间（UTC）
 * @param note        解除说明
 */
public record ReleaseHistoryEntry(
        String holdKey,
        String legalReason,
        String createdBy,
        String releasedBy,
        Instant releasedAt,
        String note) {
}
