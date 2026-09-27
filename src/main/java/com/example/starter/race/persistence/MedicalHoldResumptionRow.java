package com.example.starter.race.persistence;

/**
 * medical_hold_resumption 表行记录：医疗暂停“恢复”事件，仅插入永不更新。
 *
 * @param holdId        对应的医疗暂停ID
 * @param endAt         暂停结束时刻，Unix毫秒时间戳（UTC），右端点（不含），晚于 startAt
 * @param resumedBy     确认适赛的医疗角色ID，必须与暂停登记角色不同
 * @param fitConclusion 适赛结论（恢复时固化）
 * @param resumedAt     恢复登记的提交时刻，Unix毫秒时间戳（UTC）
 */
public record MedicalHoldResumptionRow(
        String holdId,
        long endAt,
        String resumedBy,
        String fitConclusion,
        long resumedAt
) {
}
