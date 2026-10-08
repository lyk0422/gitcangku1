package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 二次贝塞尔曲线段：固定起点 P0、控制点 P1、固定终点 P2。
 * 坐标为二维整数，单位米；控制点可与端点共线，此时曲线退化为直线。
 *
 * @param p0 固定起点（米）
 * @param p1 控制点（米）
 * @param p2 固定终点（米）
 */
public record CurveSegmentDto(
        @NotNull @Valid RoutePointDto p0,
        @NotNull @Valid RoutePointDto p1,
        @NotNull @Valid RoutePointDto p2) {
}
