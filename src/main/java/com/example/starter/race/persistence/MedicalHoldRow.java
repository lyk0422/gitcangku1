package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldStatus;

/**
 * medical_hold 表行记录（不可变医疗暂停/恢复证据；恢复仅允许 ACTIVE→RESUMED 一次状态迁移，
 * 原因、角色、时刻在写入时固化，后续操作不得改写）。
 *
 * @param holdId            医疗暂停ID，全局唯一
 * @param raceId            所属赛事ID
 * @param bib               暂停选手参赛号
 * @param status            暂停状态：ACTIVE-生效中，RESUMED-已恢复适赛
 * @param startAt           声明的暂停开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endAt             声明的暂停结束时刻，Unix毫秒时间戳（UTC），区间右开；未恢复为 null
 * @param reason            医疗暂停原因
 * @param startedBy         登记暂停的医疗角色标识
 * @param resumedBy         确认适赛的医疗角色标识（与 startedBy 不同）；未恢复为 null
 * @param fitnessConclusion 恢复时的适赛结论；未恢复为 null
 * @param createdAt         暂停登记提交时间，Unix毫秒时间戳
 * @param resumedAt         恢复确认提交时间，Unix毫秒时间戳；未恢复为 null
 */
public record MedicalHoldRow(
        String holdId,
        String raceId,
        String bib,
        MedicalHoldStatus status,
        long startAt,
        Long endAt,
        String reason,
        String startedBy,
        String resumedBy,
        String fitnessConclusion,
        long createdAt,
        Long resumedAt
) {
}
