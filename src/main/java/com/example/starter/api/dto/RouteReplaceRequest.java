package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * @param envelopeRadius  新的水平包络半径（非负整数米）；null 表示按 0 处理
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Min(0) @Max(1000000) Integer envelopeRadius,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧无包络请求：不携带包络半径，按 0 处理。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, null, requestId);
    }
}
