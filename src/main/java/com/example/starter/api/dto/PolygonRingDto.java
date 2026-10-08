package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 带孔洞多边形的单个环（外环或孔洞）顶点。
 * 顶点首尾不用重复，列表顺序即环的实际连接顺序，末点隐含连回首点。
 *
 * @param points 环顶点：孔洞 3~8 个（外环数量在请求上单独约束为 3~10 个）
 */
public record PolygonRingDto(
        @NotNull @Valid @Size(min = 3, max = 8, message = "孔洞顶点数必须在 3~8 之间")
        List<RoutePointDto> points) {
}
