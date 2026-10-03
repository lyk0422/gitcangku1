package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事芯片更换历史查询响应（只读），按登记时间与记录ID稳定排序。
 *
 * @param raceId       赛事ID
 * @param version      查询时的赛事版本（只读查询不修改版本）
 * @param replacements 更换记录列表；无更换为空列表
 */
public record ChipReplacementHistoryResponse(
        String raceId,
        int version,
        List<ChipReplacementRecordResponse> replacements
) {
}
