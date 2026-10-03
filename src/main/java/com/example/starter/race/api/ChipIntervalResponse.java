package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 芯片区间：左闭右开 [effectiveFrom, effectiveTo)。
 *
 * @param chipId        芯片ID
 * @param effectiveFrom 区间起点（含），Unix毫秒时间戳（UTC）；初始区间无起点为 null
 * @param effectiveTo   区间终点（不含），Unix毫秒时间戳（UTC）；最后区间无终点为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipIntervalResponse(
        String chipId,
        Long effectiveFrom,
        Long effectiveTo
) {
}
