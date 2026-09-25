package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * 巡航高度单位米；起止时刻为 epoch 毫秒（UTC），左闭右开，startAt &lt; endAt。
 *
 * @param routeId         航线唯一标识
 * @param points          有序航点
 * @param cruiseAltitudeM 巡航高度，单位米
 * @param startAt         巡航起始时刻，epoch 毫秒（UTC，含）
 * @param endAt           巡航结束时刻，epoch 毫秒（UTC，不含）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotNull @Min(0) @Max(100000) Integer cruiseAltitudeM,
        @NotNull @Min(0) Long startAt,
        @NotNull @Min(0) Long endAt,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容构造：默认巡航高度 0 米、时段 [0, 1)。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, 0, 0L, 1L, requestId);
    }
}
