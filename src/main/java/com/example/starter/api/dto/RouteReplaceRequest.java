package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 * 可选整体飞行窗口为 UTC epoch 毫秒左闭右开区间 [windowStart, windowEnd)，
 * 必须成对提供且开始严格早于结束；省略一对时明确将窗口设为全时。
 * 即使仅窗口改变也按 expectedVersion 推进版本。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param windowStart     整体飞行窗口起始（UTC epoch 毫秒，左闭）；与 windowEnd 成对，均 null 表示全时
 * @param windowEnd       整体飞行窗口结束（UTC epoch 毫秒，右开）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        Long windowStart,
        Long windowEnd,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：省略窗口，明确设为全时有效。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion, List<RoutePointDto> points,
                               String requestId) {
        this(routeId, expectedVersion, points, null, null, requestId);
    }
}
