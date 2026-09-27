package com.example.starter.race.api;

import com.example.starter.race.domain.EligibilityStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 单个选手医疗暂停历史查询响应（不可变证据，按开始时刻升序）。
 *
 * @param raceId      赛事ID
 * @param bib         参赛号
 * @param version     查询时赛事版本（只读不推进）
 * @param eligibility 当前资格状态：ELIGIBLE / WITHDRAWN / MEDICAL_HOLD
 * @param activeHoldId 当前生效中的暂停ID；无生效暂停为 null
 * @param holds       全部暂停/恢复记录，按开始时刻与暂停ID升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldHistoryResponse(
        String raceId,
        String bib,
        int version,
        EligibilityStatus eligibility,
        String activeHoldId,
        List<MedicalHoldResponse> holds
) {
}
