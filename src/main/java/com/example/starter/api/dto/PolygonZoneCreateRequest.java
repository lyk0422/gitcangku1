package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建带孔洞多边形禁飞区请求。
 *
 * <p>外环 3~10 个顶点，至多 2 个孔洞、每个孔洞 3~8 个顶点；整数坐标单位米，
 * 范围 [-100000,100000]；顶点首尾不重复。孔洞必须严格在外环内部、互不相交且不
 * 接触任一环边，否则返回 400。</p>
 *
 * @param zoneId    禁飞区唯一标识
 * @param outerRing 外环顶点（按实际连接顺序，末点隐含连回首点）
 * @param holes     孔洞环列表（0~2 个）
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record PolygonZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Valid @Size(min = 3, max = 10, message = "外环顶点数必须在 3~10 之间")
        List<@Valid RoutePointDto> outerRing,
        @NotNull @Valid @Size(max = 2, message = "孔洞至多 2 个")
        List<@Valid PolygonRingDto> holes,
        @NotBlank @Size(max = 64) String requestId) {
}
