package com.example.starter.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建禁飞区请求。非退化轴对齐闭矩形，坐标单位米。
 *
 * <p>时间窗口为可选 UTC 毫秒起止时刻，成对提供且开始严格早于结束，区间左闭右开；
 * 两者均省略（null）表示全时有效。</p>
 *
 * @param zoneId       禁飞区唯一标识
 * @param xMin         左边界（含），单位米
 * @param yMin         下边界（含），单位米
 * @param xMax         右边界（含），单位米
 * @param yMax         上边界（含），单位米
 * @param requestId    写操作全局唯一请求标识，用于幂等重放
 * @param windowStart  区域有效窗口开始时刻，epoch 毫秒（UTC），区间含；与 windowEnd 同省略表示全时
 * @param windowEnd    区域有效窗口结束时刻，epoch 毫秒（UTC），区间不含；必须严格晚于 windowStart
 */
public record ZoneCreateRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Min(-100000) @Max(100000) Integer xMin,
        @NotNull @Min(-100000) @Max(100000) Integer yMin,
        @NotNull @Min(-100000) @Max(100000) Integer xMax,
        @NotNull @Min(-100000) @Max(100000) Integer yMax,
        @NotBlank @Size(max = 64) String requestId,
        Long windowStart,
        Long windowEnd) {

    /** 旧请求兼容：省略时间窗口，按全时有效处理。 */
    public ZoneCreateRequest(String zoneId, Integer xMin, Integer yMin, Integer xMax, Integer yMax,
                             String requestId) {
        this(zoneId, xMin, yMin, xMax, yMax, requestId, null, null);
    }
}
