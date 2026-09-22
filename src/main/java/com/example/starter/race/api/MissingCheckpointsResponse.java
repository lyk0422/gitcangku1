package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事缺失检查点汇总响应：按参赛号字典序稳定返回每名选手缺失的检查点。
 *
 * @param raceId          赛事ID
 * @param version         查询时的赛事版本（只读查询不修改版本）
 * @param checkpointCount 赛事检查点总数；未配置检查点为 0
 * @param runners         每名选手的缺失检查点行，按参赛号字典序排列
 */
public record MissingCheckpointsResponse(
        String raceId,
        int version,
        int checkpointCount,
        List<RunnerMissingCheckpointsResponse> runners
) {
}
