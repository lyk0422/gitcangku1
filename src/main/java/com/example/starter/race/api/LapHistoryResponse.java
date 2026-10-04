package com.example.starter.race.api;

import java.util.List;

/**
 * 单个选手圈次更正历史查询响应：按（圈号, 点位顺序, 更正时间）稳定返回。
 *
 * @param raceId  赛事ID
 * @param bib     参赛号
 * @param entries 历史条目列表；无更正为空列表
 */
public record LapHistoryResponse(
        String raceId,
        String bib,
        List<LapHistoryEntryResponse> entries
) {
}