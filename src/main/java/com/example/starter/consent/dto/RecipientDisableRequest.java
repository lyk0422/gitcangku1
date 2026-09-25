package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接收方禁用请求：将接收方整体禁用，禁用后所有新批次查询 403，只允许从可用变为已禁用。
 *
 * @param requestId   幂等请求标识
 * @param recipientId 接收方标识
 */
public record RecipientDisableRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId) {
}
