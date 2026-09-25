package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 航线状态操作请求（起飞 / 取消）。
 *
 * @param routeId   航线标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteStateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String requestId) {
}
