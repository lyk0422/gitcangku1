package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        @NotBlank @Size(max = 64) String requestId) {
}
