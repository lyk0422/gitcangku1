package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 整体替换航线请求（点列与窗口整体替换）。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效，即使仅窗口改变也推进版本。
 * 省略 window 时明确按全时窗口处理（不会保留旧窗口）。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param window          新的整体飞行窗口；null/省略明确表示全时有效
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid TimeWindowDto window,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧替换请求：省略窗口明确按全时处理。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion, List<RoutePointDto> points,
                               String requestId) {
        this(routeId, expectedVersion, points, null, requestId);
    }
}
