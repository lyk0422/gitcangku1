package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 冻结解除历史条目：解除记录创建后不可变。
 *
 * @param holdKey    被解除的冻结键
 * @param releasedBy 解除人（保留角色，且不同于创建人）
 * @param note       解除说明
 * @param releasedAt 解除时间（UTC）
 */
public record HoldReleaseHistoryItem(
        String holdKey,
        String releasedBy,
        String note,
        Instant releasedAt) {
}
