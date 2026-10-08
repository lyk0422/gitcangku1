package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 二次贝塞尔曲线段请求：固定起点 P0、控制点 P1、终点 P2。
 *
 * @param p0 起点（t=0）
 * @param p1 控制点（可与端点共线，曲线退化为直线）
 * @param p2 终点（t=1），多段时须等于下一段起点
 */
public record CurveSegmentDto(
        @NotNull @Valid CurvePointDto p0,
        @NotNull @Valid CurvePointDto p1,
        @NotNull @Valid CurvePointDto p2) {
}
