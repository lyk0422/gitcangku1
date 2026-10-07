package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效（STALE）。
 *
 * <p>segments 为各航段通信档案（段数必须等于点数减一）；携带 segments 的
 * 新写请求必须同时提供非空 operator。segments 为 null 表示新版本不登记
 * 通信档案，该版本不参与链路审查。</p>
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 * @param segments        各航段通信档案（可空：新版本无档案）
 * @param operator        操作者标识；携带通信档案时必填（非空）
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        @Valid @Size(max = 49) List<SegmentProfileDto> segments,
        @Size(max = 64) String operator) {

    /** 旧式替换请求（新版本不登记通信档案）。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, requestId, null, null);
    }
}
