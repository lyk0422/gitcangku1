package com.example.starter.race.api;

import com.example.starter.race.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 罚时执行凭证响应。
 *
 * @param executionId 执行凭证ID
 * @param bib         停留选手参赛号
 * @param penaltyId   绑定的加时处罚ID
 * @param startMs     停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endMs       停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param judge       登记裁判标识
 * @param status      PENDING / CONFIRMED
 * @param creditedMs  确认时实际抵扣毫秒数；未确认为 null
 * @param confirmedBy 确认裁判标识；未确认为 null
 * @param confirmedAt 确认时间，Unix毫秒时间戳；未确认为 null
 * @param createdAt   登记时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionResponse(
        String executionId,
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