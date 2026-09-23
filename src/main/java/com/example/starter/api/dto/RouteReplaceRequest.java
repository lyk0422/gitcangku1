package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 整体替换航线请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后版本加一并使当前审核失效（即使仅窗口改变）。
 *
 * <p>窗口缺省（null）表示明确将航线窗口重置为全时：旧替换请求省略窗口
 * 时沿用全时语义。</p>
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param window          新的 UTC 毫秒整体飞行窗口；缺省表示全时
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        TimeWindowDto window,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧替换请求：省略窗口时明确将窗口设为全时。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion, List<RoutePointDto> points,
                               String requestId) {
        this(routeId, expectedVersion, points, null, requestId);
    }
}
