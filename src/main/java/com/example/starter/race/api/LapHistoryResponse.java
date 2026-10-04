package com.example.starter.race.api;

import java.util.List;

/**
 * 选手圈次更正历史：按圈号、点序、更正时间升序排列。
 *
 * @param raceId  赛事ID
 * @param bib     参赛号
 * @param entries 更正历史条目；无更正为空列表
 */
public record LapHistoryResponse(
        String raceId,
        String bib,
        List<LapHistoryEntryResponse> entries
) {
}