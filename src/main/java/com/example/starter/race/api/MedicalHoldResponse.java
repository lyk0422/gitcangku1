package com.example.starter.race.api;

import com.example.starter.race.domain.MedicalHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 医疗暂停记录响应（登记与恢复共用，未恢复时恢复相关字段缺省）。
 *
 * @param holdId            全局唯一医疗暂停ID
 * @param raceId            所属赛事ID
 * @param bib               选手参赛号
 * @param medicRole         登记暂停的医疗角色标识
 * @param reason            暂停原因
 * @param startedAt         声明的暂停开始时刻，Unix毫秒时间戳（UTC，含）
 * @param status            ACTIVE-生效中，RESUMED-已恢复
 * @param confirmRole       确认适赛的医疗角色标识；未恢复为 null
 * @param endedAt           声明的暂停结束时刻，Unix毫秒时间戳（UTC，不含）；未恢复为 null
 * @param fitnessConclusion 适赛结论；未恢复为 null
 * @param createdAt         暂停登记提交时间，Unix毫秒时间戳
 * @param resumedAt         恢复提交时间，Unix毫秒时间戳；未恢复为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        String medicRole,
        String reason,
        long startedAt,
        MedicalHoldStatus status,
        String confirmRole,
        Long endedAt,
        String fitnessConclusion,
        long createdAt,
        Long resumedAt
) {
}
