package com.example.starter.race.api;

import com.example.starter.race.domain.PenaltyCreditStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 罚时执行凭证响应。
 *
 * @param creditId     执行凭证ID
 * @param penaltyId    绑定的加时处罚ID
 * @param bib          被执行选手参赛号
 * @param startUtcMs   停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs     停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param durationMs   停留时长（整数毫秒）
 * @param registeredBy 登记裁判标识
 * @param status       PENDING / CONFIRMED
 * @param creditedMs   确认时实际抵扣毫秒数；未确认为 null
 * @param confirmedBy  确认裁判标识；未确认为 null
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param confirmedAt  确认时间，Unix毫秒时间戳；未确认为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PenaltyCreditResponse(
        String creditId,
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