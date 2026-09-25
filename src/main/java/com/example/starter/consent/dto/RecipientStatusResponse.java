package com.example.starter.consent.dto;

/**
 * 接收方状态响应。
 *
 * @param recipientId 数据接收方标识
 * @param status      状态：ACTIVE 正常 / DISABLED 已整体禁用
 */
public record RecipientStatusResponse(String recipientId, String status) {
}
