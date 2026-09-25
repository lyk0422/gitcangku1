package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * 可携带起降计划；不提供时航线不参与跑道关闭与容量检查。
 *
 * @param routeId    航线唯一标识
 * @param points     有序航点
 * @param flightPlan 起降计划（可选）
 * @param requestId  写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid FlightPlanDto flightPlan,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容构造器：不带起降计划。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, requestId);
    }
}
