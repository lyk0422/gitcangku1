package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 豁免包撤销请求。撤销未使用余额；已发生的历史核销保留不回写。
 *
 * @param permitKey 豁免包唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record PermitRevokeRequest(
        @NotBlank @Size(max = 64) String permitKey,
        @NotBlank @Size(max = 64) String requestId) {
}
