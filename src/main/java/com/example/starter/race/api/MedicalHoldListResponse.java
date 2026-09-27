package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 医疗暂停历史查询响应：按登记时间、暂停ID稳定排序。
 *
 * @param raceId 赛事ID
 * @param bib    选手参赛号；赛事级查询为 null
 * @param holds  医疗暂停记录列表（含生效中与已恢复）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldListResponse(
        String raceId,
        String bib,
        List<MedicalHoldResponse> holds
) {
}
