package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * <p>segments 为各航段通信档案（段数必须等于点数减一）；携带 segments 的
 * 新写请求必须同时提供非空 operator。segments 为 null 表示旧无通信档案航线，
 * 不参与链路审查。</p>
 *
 * @param routeId   航线唯一标识
 * @param points    有序航点
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param segments  各航段通信档案（可空：旧无档案航线）
 * @param operator  操作者标识；携带通信档案时必填（非空）
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        @Valid @Size(max = 49) List<SegmentProfileDto> segments,
        @Size(max = 64) String operator) {

    /** 旧无通信档案航线创建请求（不参与链路审查）。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, requestId, null, null);
    }
}
