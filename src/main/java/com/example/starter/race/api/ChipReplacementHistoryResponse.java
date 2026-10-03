package com.example.starter.race.api;

import java.util.List;

/**
 * 选手芯片更换历史查询响应：按生效时刻、登记时间稳定排序；只读，不修改版本。
 *
 * @param raceId       赛事ID
 * @param bib          参赛号
 * @param version      查询时的赛事版本
 * @param replacements 更换记录列表
 */
public record ChipReplacementHistoryResponse(
        String raceId,
        String bib,
        int version,
        List<ChipReplacementResponse> replacements
) {
}