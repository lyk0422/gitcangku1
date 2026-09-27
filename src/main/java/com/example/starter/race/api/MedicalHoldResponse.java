package com.example.starter.race.api;

import com.example.starter.race.domain.MedicalHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 医疗暂停记录响应（不可变证据视图）。
 *
 * @param holdId      全局唯一医疗暂停ID
 * @param raceId      所属赛事ID
 * @param bib         选手参赛号
 * @param status      ACTIVE-生效中，RESUMED-已恢复
 * @param startMs     暂停开始时刻（UTC Unix毫秒），区间左端点（含）
 * @param endMs       暂停结束时刻（UTC Unix毫秒），区间右端点（不含）；生效中为 null
 * @param durationMs  暂停时长=endMs-startMs（毫秒）；生效中为 null
 * @param reason      暂停原因
 * @param startedBy   登记暂停的医疗角色
 * @param startedAt   暂停登记时间，Unix毫秒时间戳
 * @param resumedBy   确认适赛的医疗角色；未恢复为 null
 * @param resumedAt   恢复登记时间，Unix毫秒时间戳；未恢复为 null
 * @param fitnessNote 适赛结论；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        MedicalHoldStatus status,
        long startMs,
        Long endMs,
        Long durationMs,
        String reason,
        String startedBy,
        long startedAt,
        String resumedBy,
        Long resumedAt,
        String fitnessNote
) {
}
