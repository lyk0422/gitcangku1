package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 代次冻结状态条目：status 为按当前 UTC 时刻派生的展示状态。
 *
 * @param holdKey     冻结标识
 * @param legalReason 法定事由
 * @param status      派生状态：ACTIVE 生效 / EXPIRED 已到期 / RELEASED 已解除
 * @param createdBy   创建人（保留角色操作人标识）
 * @param expiresAt   UTC 到期时刻
 */
public record HoldStatusEntry(
        String holdKey,
        String legalReason,
        String status,
        String createdBy,
        Instant expiresAt) {
}
