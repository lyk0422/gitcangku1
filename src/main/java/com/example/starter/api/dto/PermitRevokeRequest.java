package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销豁免包请求。撤销使整包失效、未使用余额不可再核销，历史核销不回写。
 *
 * @param permitKey 豁免包唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record PermitRevokeRequest(
        @NotBlank @Size(max = 64) String permitKey,
        @NotBlank @Size(max = 64) String requestId) {
}
