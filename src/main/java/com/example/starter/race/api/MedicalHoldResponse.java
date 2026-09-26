package com.example.starter.race.api;

import com.example.starter.race.domain.MedicalHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 医疗暂停明细响应（不可变记录的原样呈现；时刻均为 UTC Unix 毫秒时间戳）。
 *
 * @param holdId            医疗暂停ID
 * @param raceId            所属赛事ID
 * @param bib               暂停选手参赛号
 * @param status            暂停状态：ACTIVE-生效中，RESUMED-已恢复适赛
 * @param startAt           暂停开始时刻（区间左闭）
 * @param endAt             暂停结束时刻（区间右开）；未恢复为 null
 * @param durationMs        暂停时长=endAt-startAt（毫秒）；未恢复为 null
 * @param reason            医疗暂停原因
 * @param startedBy         登记暂停的医疗角色标识
 * @param resumedBy         确认适赛的医疗角色标识；未恢复为 null
 * @param fitnessConclusion 适赛结论；未恢复为 null
 * @param createdAt         暂停登记提交时间
 * @param resumedAt         恢复确认提交时间；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        MedicalHoldStatus status,
        long startAt,
        Long endAt,
        Long durationMs,
        String reason,
        String startedBy,
        String resumedBy,
        String fitnessConclusion,
        long createdAt,
        Long resumedAt
) {
}
