package com.example.starter.race.api;

import java.util.List;

/**
 * 单个中止事件的全体选手补偿明细响应（只读），按参赛号字典序。
 *
 * @param raceId        赛事ID
 * @param eventKey      中止事件Key
 * @param compensations 每名选手的补偿明细
 */
public record CompensationDetailResponse(
        String raceId,
        String eventKey,
        List<RunnerCompensationResponse> compensations
) {
}
