package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 停用航线请求：移除该航线全部时空桶占用。
 *
 * @param routeId   航线唯一标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteDeactivateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String requestId) {
}
