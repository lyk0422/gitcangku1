package com.example.starter.race.api;

import java.util.List;

/**
 * 单个选手圈次当前记录查询响应：按（圈号, 点位顺序）稳定返回。
 *
 * @param raceId    赛事ID
 * @param bib       参赛号
 * @param version   查询时的赛事版本（只读查询不修改版本）
 * @param totalLaps 赛事总圈数
 * @param complete  该选手所有圈点位是否齐备
 * @param laps      当前记录列表，按（圈号, 点位顺序）排列；无记录为空列表
 */
public record RunnerLapsResponse(
        String raceId,
        String bib,
        int version,
        int totalLaps,
        boolean complete,
        List<LapPassResponse> laps
) {
}