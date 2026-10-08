package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单选手区间查询中的单个计时点。
 *
 * @param checkpointCode 计时点代码；完赛区间固定为 FINISH
 * @param position       计时点顺序
 * @param lowerMs        区间下界（毫秒）；该计时点尚无区间记录为 null
 * @param upperMs        区间上界（毫秒）；该计时点尚无区间记录为 null
 * @param timingId       区间记录ID；无记录为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntervalPassResponse(
        String checkpointCode,
        int position,
        Long lowerMs,
        Long upperMs,
        String timingId
) {
}
