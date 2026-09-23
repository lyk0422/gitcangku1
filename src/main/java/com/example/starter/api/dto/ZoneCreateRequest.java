package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建禁飞区请求。非退化轴对齐闭矩形，坐标单位米。
 *
 * @param zoneId    禁飞区唯一标识
 * @param xMin      左边界（含），单位米
 * @param yMin      下边界（含），单位米
 * @param xMax      右边界（含），单位米
 * @param yMax      上边界（含），单位米
 * @param window    可选 UTC 毫秒有效窗口，缺省表示全时有效
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        TimeWindowDto window,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：不带窗口，按全时有效处理。 */
    public ZoneCreateRequest(String zoneId, Integer xMin, Integer yMin, Integer xMax, Integer yMax,
                             String requestId) {
        this(zoneId, xMin, yMin, xMax, yMax, null, requestId);
    }
}
