package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记罚时执行凭证请求（仅针对 ADD_TIME 处罚）。
 * 停留区间按左闭右开 [startUtcMs, endUtcMs)，长度为非负整数毫秒且必须大于0。
 *
 * @param creditId        执行凭证ID，全局唯一
 * @param penaltyId       绑定的加时处罚ID
 * @param bib             被执行选手参赛号，必须与处罚的被罚选手一致
 * @param startUtcMs      停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs        停留结束时刻，Unix毫秒时间戳（UTC），区间右开，必须大于开始时刻
 * @param registeredBy    登记裁判标识
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterPenaltyCreditRequest(
        @NotBlank String creditId,
        @NotBlank String penaltyId,
        @NotBlank String bib,
        @NotNull Long startUtcMs,
        @NotNull Long endUtcMs,
        @NotBlank String registeredBy,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}