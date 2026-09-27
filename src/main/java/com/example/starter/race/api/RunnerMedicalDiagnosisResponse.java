package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 选手医疗状态诊断响应（只读，不改变任何状态）：
 * 汇总当前资格状态、生效/历史暂停、被排除分段以及暂停期间到达的终点计时。
 *
 * @param raceId              赛事ID
 * @param version             查询时赛事版本
 * @param bib                 选手参赛号
 * @param entryStatus         当前成绩资格状态：RANKED/UNTIMED/MISSING_CHECKPOINT/WITHDRAWN/DISQUALIFIED
 * @param withdrawn           是否已退赛
 * @param disqualified        是否存在生效取消资格处罚
 * @param activeHoldId        当前生效医疗暂停ID；无生效暂停为 null
 * @param excludedTimings     全部被医疗暂停排除（MEDICAL_HOLD）的分段计时（按检查点顺序）
 * @param finishEventsInHold  暂停区间内提交的终点计时事件（存在时恢复会422，要求先裁定完赛状态）
 * @param holds               该选手全部医疗暂停历史（按开始时刻排列）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerMedicalDiagnosisResponse(
        String raceId,
        int version,
        String bib,
        EntryStatus entryStatus,
        boolean withdrawn,
        boolean disqualified,
        String activeHoldId,
        List<ExcludedTimingResponse> excludedTimings,
        List<FinishEventInHoldResponse> finishEventsInHold,
        List<MedicalHoldHistoryItem> holds
) {
}
