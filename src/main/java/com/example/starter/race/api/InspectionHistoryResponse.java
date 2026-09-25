package com.example.starter.race.api;

import java.util.List;

/**
 * 选手检录历史查询响应；历史不可变，复检只追加不删除，按检录时刻升序返回。
 *
 * @param raceId 赛事ID
 * @param bib    参赛号
 * @param records 全部检录记录（PASS/FAIL 均含），按检录时刻与记录顺序升序
 */
public record InspectionHistoryResponse(
        String raceId,
        String bib,
        List<InspectionRecordResponse> records
) {
}
