package com.example.starter.race.api;

import java.util.List;

/**
 * 选手被排除分段计时的诊断查询响应。
 *
 * @param raceId          赛事ID
 * @param bib             选手参赛号
 * @param excludedTimings 被排除出排名的分段计时，按检查点顺序排列；无为空列表
 */
public record ExcludedTimingsResponse(
        String raceId,
        String bib,
        List<ExcludedTimingResponse> excludedTimings
) {
}
