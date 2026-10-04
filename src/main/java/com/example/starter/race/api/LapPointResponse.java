package com.example.starter.race.api;

import com.example.starter.race.domain.LapPoint;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手某圈某点的当前状态；未提交的点 lapPassId/elapsedMillis 为 null。
 *
 * @param lapNo         圈号，从1开始
 * @param point         圈内点：A / B / FINISH
 * @param lapPassId     当前记录ID；未提交为 null
 * @param elapsedMillis 累计耗时（毫秒，非负）；未提交为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LapPointResponse(
        int lapNo,
        LapPoint point,
        String lapPassId,
        Long elapsedMillis
) {
}