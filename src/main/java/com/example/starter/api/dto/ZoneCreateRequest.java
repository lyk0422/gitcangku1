package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建禁飞区请求。非退化轴对齐闭矩形，坐标单位米。
 * 可同时登记初始高度带（可选）；高度带左闭右开、区域内不得重叠。
 *
 * @param zoneId    禁飞区唯一标识
 * @param xMin      左边界（含），单位米
 * @param yMin      下边界（含），单位米
 * @param xMax      右边界（含），单位米
 * @param yMax      上边界（含），单位米
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param bands     初始高度带（可选，null 或空表示不登记）
 */
public record ZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotBlank @Size(max = 64) String requestId,
        @Valid List<AltitudeBandDto> bands) {

    /** 兼容构造：不登记初始高度带。 */
    public ZoneCreateRequest(String zoneId, Integer xMin, Integer yMin,
                             Integer xMax, Integer yMax, String requestId) {
        this(zoneId, xMin, yMin, xMax, yMax, requestId, null);
    }
}
