package com.example.starter.race.persistence;

import com.example.starter.race.domain.PenaltyCreditStatus;

/**
 * penalty_credit 表行记录：仅针对 ADD_TIME 处罚的赛中停留执行凭证。
 *
 * @param creditId     执行凭证ID，全局唯一
 * @param raceId       所属赛事ID
 * @param penaltyId    绑定的加时处罚ID
 * @param bib          被执行选手参赛号（与处罚一致）
 * @param startUtcMs   停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs     停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param durationMs   停留时长=endUtcMs-startUtcMs（整数毫秒，>=1）
 * @param registeredBy 登记裁判标识
 * @param status       PENDING-待确认，CONFIRMED-已确认
 * @param creditedMs   确认时实际抵扣毫秒数（不超过该处罚剩余未执行量）；未确认为 null
 * @param confirmedBy  确认裁判标识，必须与登记裁判不同；未确认为 null
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param confirmedAt  确认时间，Unix毫秒时间戳；未确认为 null
 */
public record PenaltyCreditRow(
        String creditId,
        String raceId,
        String penaltyId,
        String bib,
        long startUtcMs,
        long endUtcMs,
        long durationMs,
        String registeredBy,
        PenaltyCreditStatus status,
        Long creditedMs,
        String confirmedBy,
        long createdAt,
        Long confirmedAt
) {
}