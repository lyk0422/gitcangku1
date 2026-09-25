package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列与巡航参数请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param cruiseAltitudeM 新的巡航高度，单位米
 * @param startAt         新的巡航起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           新的巡航结束时刻，epoch 毫秒（UTC，不含）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotNull @Min(0) @Max(100000) Integer cruiseAltitudeM,
        @NotNull @Min(0) Long startAt,
        @NotNull @Min(0) Long endAt,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容构造：默认巡航高度 0 米、时段 [0, 1)。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, 0, 0L, 1L, requestId);
    }
}
