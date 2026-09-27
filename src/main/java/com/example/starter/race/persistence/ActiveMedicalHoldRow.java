package com.example.starter.race.persistence;

/**
 * medical_hold_active 表行记录：当前生效（未恢复）的医疗暂停，仅作并发唯一兜底，
 * 不是证据来源（证据以 medical_hold / medical_hold_resumption 为准）。
 *
 * @param raceId  所属赛事ID
 * @param bib     选手参赛号
 * @param holdId  生效中的医疗暂停ID
 * @param startAt 暂停开始时刻，Unix毫秒时间戳（UTC）
 */
public record ActiveMedicalHoldRow(
        String raceId,
        String bib,
        String holdId,
        long startAt
) {
}
