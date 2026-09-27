package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldView;

/**
 * medical_hold 表行记录（不可变医疗暂停证据；恢复时回填恢复字段）。
 * 时间均为绝对 UTC 毫秒时间戳，区间 [startAt, endAt) 左闭右开。
 *
 * @param holdId        医疗暂停ID，全局唯一
 * @param raceId        所属赛事ID
 * @param bib           被暂停选手参赛号
 * @param startAt       暂停开始绝对UTC时刻（含）
 * @param endAt         暂停结束绝对UTC时刻（不含）；null 表示生效中
 * @param holdReason    登记暂停原因
 * @param startRole     登记暂停的医疗角色标识
 * @param resumeRole    确认恢复的医疗角色标识（与 startRole 不同）；未恢复为 null
 * @param fitConclusion 恢复时的适赛结论；未恢复为 null
 * @param startedAt     开始暂停的提交时刻（绝对UTC毫秒时间戳）
 * @param resumedAt     恢复的提交时刻（绝对UTC毫秒时间戳）；未恢复为 null
 */
public record MedicalHoldRow(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        Long endAt,
        String holdReason,
        String startRole,
        String resumeRole,
        String fitConclusion,
        long startedAt,
        Long resumedAt
) implements MedicalHoldView {
}
