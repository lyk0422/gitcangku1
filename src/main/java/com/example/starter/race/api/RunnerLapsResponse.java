package com.example.starter.race.api;

import java.util.List;

/**
 * 选手圈次总览：按圈号与点序（A、B、FINISH）展开全部 总圈数×3 个点。
 *
 * @param raceId    赛事ID
 * @param bib       参赛号
 * @param version   查询时赛事版本号
 * @param totalLaps 赛事总圈数
 * @param complete  全部圈次点是否已齐备且严格递增（完整才可参与封榜排名）
 * @param laps      全部圈次点状态，缺失点记录ID与耗时为 null
 */
public record RunnerLapsResponse(
        String raceId,
        String bib,
        int version,
        int totalLaps,
        boolean complete,
        List<LapPointResponse> laps
) {
}