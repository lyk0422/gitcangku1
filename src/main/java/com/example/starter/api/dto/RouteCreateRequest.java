package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * <p>时间窗口为可选 UTC 毫秒起止时刻，成对提供且开始严格早于结束，区间左闭右开；
 * 两者均省略（null）表示全时有效。整条航线统一使用该窗口，不估计每一航点到达时刻。</p>
 *
 * @param routeId     航线唯一标识
 * @param points      有序航点
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 * @param windowStart 航线整体飞行窗口开始时刻，epoch 毫秒（UTC），区间含；与 windowEnd 同省略表示全时
 * @param windowEnd   航线整体飞行窗口结束时刻，epoch 毫秒（UTC），区间不含；必须严格晚于 windowStart
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        Long windowStart,
        Long windowEnd) {

    /** 旧请求兼容：省略时间窗口，按全时有效处理。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, requestId, null, null);
    }
}
