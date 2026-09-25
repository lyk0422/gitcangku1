package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接收方登记请求：创建一个可被整体启用/禁用的数据接收方。
 *
 * @param requestId   幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param recipientId 接收方标识（合成字符串）
 * @param displayName 接收方展示名（合成字符串）
 */
public record RecipientRegisterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId,
        @NotBlank @Size(max = 256) String displayName) {
}
