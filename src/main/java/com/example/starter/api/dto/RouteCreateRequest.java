package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * 可选整体飞行窗口为 UTC epoch 毫秒左闭右开区间 [windowStart, windowEnd)，
 * 必须成对提供且开始严格早于结束；缺省一对表示全时有效。
 *
 * @param routeId     航线唯一标识
 * @param points      有序航点
 * @param windowStart 整体飞行窗口起始（UTC epoch 毫秒，左闭）；与 windowEnd 成对，均 null 表示全时
 * @param windowEnd   整体飞行窗口结束（UTC epoch 毫秒，右开）
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        Long windowStart,
        Long windowEnd,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：不带窗口，全时有效。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, null, requestId);
    }
}
