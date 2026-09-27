package com.example.starter.race.persistence;

import com.example.starter.race.domain.MedicalHoldView;

/**
 * 医疗暂停开始事件与恢复事件合成的完整暂停视图（用于历史与诊断查询）。
 * 区间左闭右开 [startAt, endAt)；endAt 为 null 表示暂停生效中。
 *
 * @param holdId        医疗暂停ID
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param startAt       暂停开始时刻，Unix毫秒时间戳（UTC）
 * @param reason        暂停原因
 * @param startedBy     登记开始的医疗角色ID
 * @param createdAt     开始登记提交时刻，Unix毫秒时间戳（UTC）
 * @param endAt         暂停结束时刻，Unix毫秒时间戳（UTC）；生效中为 null
 * @param resumedBy     确认适赛的医疗角色ID；生效中为 null
 * @param fitConclusion 适赛结论；生效中为 null
 * @param resumedAt     恢复登记提交时刻，Unix毫秒时间戳（UTC）；生效中为 null
 */
public record MedicalHoldDetailRow(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        String reason,
        String startedBy,
        long createdAt,
        Long endAt,
        String resumedBy,
        String fitConclusion,
        Long resumedAt
) implements MedicalHoldView {
}
