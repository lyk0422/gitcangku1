package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 材料批次撤销请求。撤销只置状态，已固化进证据包的摘要不变；撤销后的引用返回 422。
 *
 * @param requestId  全局唯一请求标识（幂等键）
 * @param reason     撤销原因，可空，null 表示未填写
 */
public record MaterialRevokeRequest(
        @NotBlank String requestId,
        String reason) {
}
