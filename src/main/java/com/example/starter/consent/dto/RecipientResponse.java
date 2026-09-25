package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.RecipientStatus;

/**
 * 接收方响应：返回接收方标识、状态与登记时间（UTC）。
 *
 * @param recipientId 接收方标识
 * @param status      状态：ENABLED 可用 / DISABLED 已整体禁用
 * @param displayName 接收方展示名
 * @param createdAt   登记时间（UTC）
 */
public record RecipientResponse(String recipientId, RecipientStatus status,
                                String displayName, Instant createdAt) {
}
