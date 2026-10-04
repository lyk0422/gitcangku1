package com.example.starter.race.api;

import com.example.starter.race.domain.ExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 执行凭证信息响应；起止为 UTC Unix 毫秒时间戳，区间左闭右开。
 *
 * @param voucherId   全局凭证ID
 * @param penaltyId   关联的加时处罚ID
 * @param bib         被执行选手参赛号
 * @param startUtcMs  停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs    停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param referee     登记裁判
 * @param status      PENDING / CONFIRMED
 * @param confirmedBy 确认裁判；未确认为 null
 * @param confirmedAt 确认时间，Unix毫秒时间戳；未确认为 null
 * @param creditedMs  确认时实际抵扣毫秒数；未确认为 0
 * @param createdAt   登记时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecutionResponse(
        String voucherId,
        String penaltyId,
        String bib,
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