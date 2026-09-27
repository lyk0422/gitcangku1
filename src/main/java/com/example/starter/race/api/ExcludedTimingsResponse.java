package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事级被排除分段计时诊断查询响应：按参赛号、检查点顺序稳定排序。
 *
 * @param raceId          赛事ID
 * @param excludedTimings 被医疗暂停排除（MEDICAL_HOLD）的分段计时列表
 */
public record ExcludedTimingsResponse(
        String raceId,
        List<ExcludedTimingResponse> excludedTimings
) {
}
