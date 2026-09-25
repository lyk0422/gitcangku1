package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * <p>cruiseAltitude/startTime/endTime 为本题新增的巡航高度（米）与 UTC 时段
 * （epoch 毫秒，左闭右开）；不携带时为 null，表示仅二维审查的历史航线。
 * 三者要么全部提供（startTime &lt; endTime，业务层校验），要么全部缺省。</p>
 *
 * @param routeId        航线唯一标识
 * @param points         有序航点
 * @param requestId      写操作全局唯一请求标识，用于幂等重放
 * @param cruiseAltitude 巡航高度，单位米；null 表示未登记高度
 * @param startTime      UTC 时段起始，epoch 毫秒（含）；null 表示未登记时段
 * @param endTime        UTC 时段结束，epoch 毫秒（不含）；null 表示未登记时段
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        Integer cruiseAltitude,
        Long startTime,
        Long endTime) {

    /** 兼容历史调用：仅二维属性，不登记巡航高度与时段。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, requestId, null, null, null);
    }
}
