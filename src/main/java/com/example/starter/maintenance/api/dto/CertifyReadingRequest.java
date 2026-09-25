package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 认证读数请求：将指定读数标记为设备当前已认证读数（同设备同时仅一个当前已认证读数）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 */
public record CertifyReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion) {
}
