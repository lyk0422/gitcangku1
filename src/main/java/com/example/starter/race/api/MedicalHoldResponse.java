package com.example.starter.race.api;

import com.example.starter.race.domain.MedicalHoldStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 医疗暂停明细响应（不可变记录：角色、时刻、原因与适赛结论在写入时固化）。
 *
 * @param holdId            医疗暂停ID
 * @param raceId            所属赛事ID
 * @param bib               暂停选手参赛号
 * @param startAt           暂停开始时刻，UTC Unix毫秒（区间左闭）
 * @param endAt             暂停结束时刻，UTC Unix毫秒（区间右开）；生效中为 null
 * @param reason            暂停原因
 * @param startedBy         登记暂停的医疗角色标识
 * @param startRecordedAt   暂停登记提交时刻，UTC Unix毫秒
 * @param endedBy           确认适赛恢复的医疗角色标识；生效中为 null
 * @param fitnessConclusion 恢复时的适赛结论；生效中为 null
 * @param endRecordedAt     恢复提交时刻，UTC Unix毫秒；生效中为 null
 * @param status            ACTIVE-生效中，RESUMED-已恢复
 * @param createdAt         记录创建时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MedicalHoldResponse(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        Long endAt,
        String reason,
        String startedBy,
        long startRecordedAt,
        String endedBy,
        String fitnessConclusion,
        Long endRecordedAt,
        MedicalHoldStatus status,
        long createdAt
) {
}
