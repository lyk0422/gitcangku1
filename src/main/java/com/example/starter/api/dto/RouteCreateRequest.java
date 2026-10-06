package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * 可携带每段通信档案（段数 = 点数 - 1）；携带档案时操作者必填（服务层校验）。
 *
 * @param routeId   航线唯一标识
 * @param points    有序航点
 * @param segments  每段通信档案（可空：无档案的旧式航线，不适用通信审查）
 * @param operator  操作者标识；登记通信档案的写请求必须非空
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid @Size(max = 49) List<SegmentLinkDto> segments,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 无通信档案的旧式创建请求（不适用通信审查）。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, null, requestId);
    }
}
