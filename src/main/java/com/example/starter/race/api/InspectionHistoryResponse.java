package com.example.starter.race.api;

import java.util.List;

/**
 * 选手检录历史查询结果：历史按检录时刻升序（不可变、只追加）。
 *
 * @param raceId  赛事ID
 * @param bib     选手参赛号
 * @param entries 检录历史条目，按 inspectedAt、inspectionId 升序
 */
public record InspectionHistoryResponse(
        String raceId,
        String bib,
        List<InspectionHistoryEntryResponse> entries
) {
}
