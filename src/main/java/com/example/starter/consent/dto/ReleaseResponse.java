package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.HoldStatus;

/**
 * 保留冻结解除响应：解除结果与不可变解除记录。
 *
 * @param holdKey    冻结标识
 * @param status     状态，解除成功固定为 RELEASED
 * @param releasedBy 解除人（保留角色操作人标识）
 * @param releasedAt 解除时间（UTC）
 * @param note       解除说明
 */
public record ReleaseResponse(
        String holdKey,
        HoldStatus status,
        String releasedBy,
        Instant releasedAt,
        String note) {
}
