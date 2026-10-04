package com.example.starter.race.persistence;

import com.example.starter.race.domain.ExecutionStatus;

/**
 * penalty_execution 表行记录：仅绑定加时处罚的赛中停留执行凭证。
 *
 * @param executionId 执行凭证ID，全局唯一
 * @param raceId      所属赛事ID
 * @param bib         停留选手参赛号
 * @param penaltyId   绑定的加时处罚ID
 * @param startMs     停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endMs       停留结束时刻，Unix毫秒时间戳（UTC），区间右开；长度=endMs-startMs
 * @param judge       登记裁判标识
 * @param status      凭证状态：PENDING / CONFIRMED
 * @param creditedMs  确认时实际抵扣毫秒数（按处罚剩余量封顶）；未确认为 null
 * @param confirmedBy 确认裁判标识，必须与登记裁判不同；未确认为 null
 * @param confirmedAt 确认时间，Unix毫秒时间戳；未确认为 null
 * @param createdAt   登记时间，Unix毫秒时间戳
 */
public record ExecutionRow(
        String executionId,
        String raceId,
        String bib,
        String penaltyId,
        long startMs,
        long endMs,
        String judge,
        ExecutionStatus status,
        Long creditedMs,
        String confirmedBy,
        Long confirmedAt,
        long createdAt
) {
}