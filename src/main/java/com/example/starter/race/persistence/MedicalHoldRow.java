package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldStatus;

/**
 * medical_hold 表行记录（不可变医疗暂停/恢复证据）。
 *
 * @param holdId           全局唯一医疗暂停ID
 * @param raceId           所属赛事ID
 * @param bib              暂停选手参赛号
 * @param startAt          暂停开始时刻，UTC Unix毫秒（区间左闭）
 * @param endAt            暂停结束时刻，UTC Unix毫秒（区间右开），必须晚于 startAt；生效中为 null
 * @param reason           暂停原因
 * @param startedBy        登记暂停的医疗角色标识
 * @param startRecordedAt  暂停登记提交时刻，UTC Unix毫秒
 * @param endedBy          确认适赛恢复的医疗角色标识（与 startedBy 不同）；生效中为 null
 * @param fitnessConclusion 恢复时的适赛结论；生效中为 null
 * @param endRecordedAt    恢复提交时刻，UTC Unix毫秒；生效中为 null
 * @param status           ACTIVE-生效中，RESUMED-已恢复
 * @param activeBib        生效中暂停的选手参赛号；恢复后为 null（唯一键用）
 * @param createdAt        记录创建时间，Unix毫秒时间戳
 */
public record MedicalHoldRow(
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
        String activeBib,
        long createdAt
) {
}
