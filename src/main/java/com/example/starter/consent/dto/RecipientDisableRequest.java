package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接收方整体禁用请求：禁用后该接收方所有新批次查询返回 403，即使证明仍有效。
 *
 * @param requestId   幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param recipientId 数据接收方标识
 */
public record RecipientDisableRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId) {
}
