package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 *
 * <p>整体替换可带时间窗口：窗口成对提供且开始严格早于结束，区间左闭右开；
 * 旧替换请求省略窗口时明确将窗口设为全时（不沿用被替换版本的窗口）。
 * 任何替换均按原 expectedVersion 推进版本，即使仅窗口改变也使当前结论失效。</p>
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 * @param windowStart     新整体飞行窗口开始时刻，epoch 毫秒（UTC），区间含；与 windowEnd 同省略表示全时
 * @param windowEnd       新整体飞行窗口结束时刻，epoch 毫秒（UTC），区间不含；必须严格晚于 windowStart
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        Long windowStart,
        Long windowEnd) {

    /** 旧替换请求兼容：省略窗口时明确将窗口设为全时。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion, List<RoutePointDto> points,
                               String requestId) {
        this(routeId, expectedVersion, points, requestId, null, null);
    }
}
