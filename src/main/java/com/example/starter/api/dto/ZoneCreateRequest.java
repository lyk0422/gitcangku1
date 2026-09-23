package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建禁飞区请求。非退化轴对齐闭矩形，坐标单位米。
 * 可选有效窗口为 UTC epoch 毫秒左闭右开区间 [windowStart, windowEnd)，
 * 必须成对提供且开始严格早于结束；缺省一对表示全时有效。
 *
 * @param zoneId      禁飞区唯一标识
 * @param xMin        左边界（含），单位米
 * @param yMin        下边界（含），单位米
 * @param xMax        右边界（含），单位米
 * @param yMax        上边界（含），单位米
 * @param windowStart 有效窗口起始（UTC epoch 毫秒，左闭）；与 windowEnd 成对，均 null 表示全时
 * @param windowEnd   有效窗口结束（UTC epoch 毫秒，右开）
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record ZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        Long windowStart,
        Long windowEnd,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容旧请求：不带窗口，全时有效。 */
    public ZoneCreateRequest(String zoneId, Integer xMin, Integer yMin, Integer xMax, Integer yMax,
                             String requestId) {
        this(zoneId, xMin, yMin, xMax, yMax, null, null, requestId);
    }
}
