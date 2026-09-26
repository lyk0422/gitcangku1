package com.example.starter.race.api;

import java.util.List;

/**
 * 医疗暂停历史查询响应：按登记时间与暂停ID稳定排列；只读，不修改任何状态。
 *
 * @param raceId  赛事ID
 * @param bib     限定查询的参赛号；赛事级查询为 null
 * @param version 查询时的赛事版本（只读查询不修改版本）
 * @param holds   医疗暂停历史明细
 */
public record MedicalHoldHistoryResponse(
        String raceId,
        String bib,
        int version,
        List<MedicalHoldResponse> holds
) {
}
