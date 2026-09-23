package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事全部选手的中止补偿明细（只读），按参赛号字典序稳定返回。
 *
 * @param raceId  赛事ID
 * @param version 当前赛事版本（只读查询不修改版本）
 * @param runners 每名选手的补偿明细
 */
public record CompensationResponse(
        String raceId,
        int version,
        List<RunnerCompensationResponse> runners
) {
}
