package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId) {
}
