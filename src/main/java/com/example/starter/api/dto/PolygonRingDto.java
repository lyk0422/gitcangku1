package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 带孔洞多边形禁飞区的一个环（外环或孔洞）。
 * 顶点首尾不重复，顺序即环的实际连接关系。
 *
 * @param vertices 有序环顶点；外环 3~10 个，孔洞 3~8 个
 */
public record PolygonRingDto(
        @NotNull @Valid @Size(min = 3, max = 8) List<RoutePointDto> vertices) {
}
