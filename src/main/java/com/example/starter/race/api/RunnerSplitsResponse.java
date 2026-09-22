package com.example.starter.race.api;

import java.util.List;

/**
 * 单个选手的分段查询响应：按检查点顺序排列，缺失检查点 elapsedMillis 为 null。
 *
 * @param raceId 赛事ID
 * @param bib    参赛号
 * @param splits 按检查点顺序（seq 升序）排列的分段明细
 */
public record RunnerSplitsResponse(
        String raceId,
        String bib,
        List<SplitDetailResponse> splits
) {
}
