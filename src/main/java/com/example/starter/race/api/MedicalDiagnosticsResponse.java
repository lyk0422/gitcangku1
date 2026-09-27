package com.example.starter.race.api;

import com.example.starter.race.domain.EligibilityStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 选手医疗暂停诊断：资格状态、生效暂停与被排除计时明细。只读，不改变状态。
 *
 * @param raceId          赛事ID
 * @param bib             参赛号
 * @param version         查询时赛事版本（只读不推进）
 * @param eligibility     当前资格状态
 * @param activeHoldId    生效中的暂停ID；无则为 null
 * @param excludedCount   被排除计时总条数（实际值）
 * @param excludedTimings 被排除计时明细，按检查点顺序升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalDiagnosticsResponse(
        String raceId,
        String bib,
        int version,
        EligibilityStatus eligibility,
        String activeHoldId,
        int excludedCount,
        List<ExcludedTimingResponse> excludedTimings
) {
}
