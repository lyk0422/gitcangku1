package com.example.starter.race.api;

/**
 * 医疗暂停恢复响应。
 *
 * @param holdId        医疗暂停ID
 * @param raceId        所属赛事ID
 * @param bib           选手参赛号
 * @param startAt       暂停开始时刻，Unix毫秒时间戳（UTC）
 * @param endAt         暂停结束时刻，Unix毫秒时间戳（UTC），右端点（不含）
 * @param durationMs    实际暂停时长=endAt-startAt（毫秒，实际值）
 * @param resumedBy     确认适赛的医疗角色ID（与登记角色不同）
 * @param fitConclusion 适赛结论（已固化）
 * @param resumedAt     恢复登记提交时刻，Unix毫秒时间戳（UTC）
 */
public record MedicalHoldResumptionResponse(
        String holdId,
        String raceId,
        String bib,
        long startAt,
        long endAt,
        long durationMs,
        String resumedBy,
        String fitConclusion,
        long resumedAt
) {
}
