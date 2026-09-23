package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * @param routeId   航线唯一标识
 * @param points    有序航点
 * @param window    可选 UTC 毫秒整体飞行窗口，缺省表示全时有效
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        TimeWindowDto window,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：不带窗口，按全时有效处理。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, requestId);
    }
}
