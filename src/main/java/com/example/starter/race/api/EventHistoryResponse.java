package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 赛事中止/恢复事件历史响应（只读），按中止开始点升序。
 *
 * @param raceId  赛事ID
 * @param version 查询时的赛事版本（只读查询不修改版本）
 * @param events  全部中止事件；无事件为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventHistoryResponse(
        String raceId,
        int version,
        List<SuspensionEventResponse> events
) {
}
