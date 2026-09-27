package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 医疗暂停/恢复的不可变记录响应。时间均为绝对 UTC 毫秒时间戳，区间左闭右开。
 *
 * @param holdId        医疗暂停ID
 * @param raceId        所属赛事ID
 * @param bib           被暂停选手参赛号
 * @param startAt       暂停开始绝对UTC时刻（含）
 * @param endAt         暂停结束绝对UTC时刻（不含）；生效中为 null
 * @param reason        登记暂停原因
 * @param startRole     登记暂停的医疗角色标识
 * @param resumeRole    确认恢复的医疗角色标识；未恢复为 null
 * @param fitConclusion 恢复时的适赛结论；未恢复为 null
 * @param startedAt     开始暂停的提交时刻
 * @param resumedAt     恢复的提交时刻；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        Long endAt,
        String reason,
        String startRole,
        String resumeRole,
        String fitConclusion,
        long startedAt,
        Long resumedAt
) {
}
