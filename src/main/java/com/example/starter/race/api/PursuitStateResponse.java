package com.example.starter.race.api;

import java.util.List;

/**
 * 追逐赛当前状态响应（配置、轨迹版本与当前裁定）。
 *
 * @param raceId        赛事ID
 * @param version       当前赛事版本号
 * @param pairs         已固定的追逐对（按序号升序）
 * @param trajectories  当前轨迹（按参赛号字典序）
 * @param adjudications 当前裁定（按追逐对序号升序）
 */
public record PursuitStateResponse(
        String raceId,
        int version,
        List<PursuitPairResponse> pairs,
        List<TrajectoryResponse> trajectories,
        List<AdjudicationResponse> adjudications
) {
}
