package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单选手芯片归属诊断响应（只读）：当前芯片、全部区间与每条读数的归属。
 *
 * @param raceId        赛事ID
 * @param bib           参赛号
 * @param version       查询时的赛事版本（只读查询不修改版本）
 * @param currentChipId 当前生效芯片ID；尚无更换记录为 null
 * @param intervals     芯片区间列表，按生效时刻升序；无更换记录为空列表
 * @param readings      分段读数归属明细，按检查点顺序排列
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipAttributionResponse(
        String raceId,
        String bib,
        int version,
        String currentChipId,
        List<ChipIntervalResponse> intervals,
        List<ChipReadingResponse> readings
) {
}
