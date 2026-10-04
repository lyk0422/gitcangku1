package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记时间处罚的赛中停留执行凭证请求。
 * 起止为 UTC Unix 毫秒时间戳，区间左闭右开，长度=endUtcMs-startUtcMs 为整数毫秒。
 *
 * @param voucherId       凭证ID，全局唯一
 * @param bib             被执行选手参赛号，必须与处罚对象一致
 * @param startUtcMs      停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endUtcMs        停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param referee         登记凭证的裁判
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterExecutionRequest(
        @NotBlank String voucherId,
        @NotBlank String bib,
        @NotNull Long startUtcMs,
        @NotNull Long endUtcMs,
        @NotBlank String referee,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}