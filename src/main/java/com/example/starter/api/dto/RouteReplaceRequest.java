package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 * 可携带每段通信档案（段数 = 点数 - 1）；携带档案时操作者必填（服务层校验）。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param segments        每段通信档案（可空：新版本无档案，不适用通信审查）
 * @param operator        操作者标识；登记通信档案的写请求必须非空
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid @Size(max = 49) List<SegmentLinkDto> segments,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 无通信档案的旧式替换请求（新版本不适用通信审查）。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, null, null, requestId);
    }
}
