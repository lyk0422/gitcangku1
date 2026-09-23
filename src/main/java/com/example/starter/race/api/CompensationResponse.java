package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 赛事全部选手的中止补偿明细响应（只读），按参赛号字典序。
 *
 * @param raceId  赛事ID
 * @param version 查询时的赛事版本（只读查询不修改版本）
 * @param runners 每名选手的补偿明细
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompensationResponse(
        String raceId,
        int version,
        List<RunnerCompensationResponse> runners
) {
}
