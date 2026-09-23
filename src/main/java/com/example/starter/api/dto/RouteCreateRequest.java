package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * window 为整条航线统一使用的可选 UTC 毫秒半开飞行窗口；不提供表示全时。
 *
 * @param routeId   航线唯一标识
 * @param points    有序航点
 * @param window    可选整体飞行窗口 [startUtcMillis, endUtcMillis)；null 表示全时
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid TimeWindowDto window,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：不带窗口即全时有效。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, requestId);
    }
}
