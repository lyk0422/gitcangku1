package com.example.starter.race.persistence;

import com.example.starter.race.domain.ExecutionStatus;

/**
 * penalty_execution 表行记录：时间处罚的赛中停留执行凭证。
 *
 * @param voucherId   凭证ID，全局唯一
 * @param raceId      所属赛事ID
 * @param bib         被执行选手参赛号，必须与处罚对象一致
 * @param penaltyId   关联的加时处罚ID
 * @param startUtcMs  停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs    停留结束时刻，Unix毫秒时间戳（UTC），区间右开；长度=endUtcMs-startUtcMs 整数毫秒
 * @param referee     登记凭证的裁判
 * @param status      凭证状态：PENDING-待确认，CONFIRMED-已确认
 * @param confirmedBy 确认裁判（必须与登记裁判不同）；未确认为 null
 * @param confirmedAt 确认时间，Unix毫秒时间戳；未确认为 null
 * @param creditedMs  确认时实际抵扣毫秒数（不超过该处罚剩余未执行量）；未确认为 0
 * @param createdAt   登记时间，Unix毫秒时间戳
 */
public record ExecutionRow(
        String voucherId,
        String raceId,
        String bib,
        String penaltyId,
        long startUtcMs,
        long endUtcMs,
        String referee,
        ExecutionStatus status,
        String confirmedBy,
        Long confirmedAt,
        long creditedMs,
        long createdAt
) {
}