package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldView;

/**
 * medical_hold 表行记录：医疗暂停“开始”事件，仅插入永不更新。
 *
 * @param holdId    医疗暂停ID，全局唯一
 * @param raceId    所属赛事ID
 * @param bib       选手参赛号
 * @param startAt   暂停开始时刻，Unix毫秒时间戳（UTC），左闭端点
 * @param reason    暂停原因（登记时固化）
 * @param startedBy 登记开始的医疗角色ID
 * @param createdAt 暂停开始登记的提交时刻，Unix毫秒时间戳（UTC）
 */
public record MedicalHoldRow(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        String reason,
        String startedBy,
        long createdAt
) implements MedicalHoldView {

    @Override
    public Long endAt() {
        return null;
    }
}
