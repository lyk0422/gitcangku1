package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建二次曲线航线请求。1~5 个曲线段，相邻段首尾端点必须相等。
 *
 * @param routeId   航线唯一标识
 * @param segments  有序二次曲线段（每段固定 P0/P1/P2，控制点可退化为直线）
 * @param operator  非空操作者标识
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record CurveRouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 1, max = 5) List<CurveSegmentDto> segments,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
