package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事中止事件历史响应（只读），按中止开始耗时升序。
 *
 * @param raceId  赛事ID
 * @param version 查询时的赛事版本（只读查询不修改版本）
 * @param events  全部中止事件（含中止中与已恢复）
 */
public record SuspensionHistoryResponse(
        String raceId,
        int version,
        List<SuspensionEventResponse> events
) {
}
