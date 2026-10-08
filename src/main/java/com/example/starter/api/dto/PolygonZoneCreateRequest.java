package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建带孔洞多边形禁飞区请求。
 *
 * <p>外环 3~10 个顶点、至多 2 个各 3~8 顶点的孔洞；顶点首尾不重复，
 * 顺序即环的实际连接关系。各环必须闭合、非退化且无自交；
 * 孔洞严格位于外环内部，孔洞之间互不相交且不接触任一环边，否则 400。</p>
 *
 * @param zoneId    禁飞区唯一标识（与矩形禁飞区共用命名空间，不可冲突）
 * @param outer     外环有序顶点（3~10 个）
 * @param holes     孔洞环列表（0~2 个，每个 3~8 顶点）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record PolygonZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Valid @Size(min = 3, max = 10) List<RoutePointDto> outer,
        @NotNull @Valid @Size(max = 2) List<@Valid PolygonRingDto> holes,
        @NotBlank @Size(max = 64) String requestId) {
}
