package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldStatus;

/**
 * medical_hold 表行记录（医疗暂停与恢复的不可变证据）。
 *
 * @param holdId     全局唯一医疗暂停ID
 * @param raceId     所属赛事ID
 * @param bib        选手参赛号
 * @param startMs    暂停开始时刻（UTC Unix毫秒），区间左端点（含）
 * @param endMs      暂停结束时刻（UTC Unix毫秒），区间右端点（不含）；生效中为 null
 * @param reason     暂停原因
 * @param startedBy  登记暂停的医疗角色
 * @param startedAt  暂停登记时间，Unix毫秒时间戳
 * @param resumedBy  确认适赛的医疗角色（与登记角色不同）；未恢复为 null
 * @param resumedAt  恢复登记时间，Unix毫秒时间戳；未恢复为 null
 * @param fitnessNote 适赛结论；未恢复为 null
 */
public record MedicalHoldRow(
        String holdId,
        String raceId,
        String bib,
        long startMs,
        Long endMs,
        String reason,
        String startedBy,
        long startedAt,
        String resumedBy,
        Long resumedAt,
        String fitnessNote
) {

    /** 派生状态：endMs 为 null 表示生效中。 */
    public MedicalHoldStatus status() {
        return endMs == null ? MedicalHoldStatus.ACTIVE : MedicalHoldStatus.RESUMED;
    }
}
