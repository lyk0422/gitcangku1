package com.example.starter.race.api;

import java.util.List;

/**
 * 选手医疗暂停历史响应：按登记提交时刻与暂停ID稳定排序。
 *
 * @param raceId 赛事ID
 * @param bib    选手参赛号
 * @param holds  该选手全部医疗暂停记录（含已恢复）
 */
public record MedicalHoldHistoryResponse(
        String raceId,
        String bib,
        List<MedicalHoldResponse> holds
) {
}
