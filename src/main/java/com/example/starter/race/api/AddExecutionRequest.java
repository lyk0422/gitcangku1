package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记罚时执行凭证请求（仅加时处罚可登记）。
 * 停留区间为[startMs, endMs)左闭右开，长度为正整数毫秒。
 *
 * @param executionId     执行凭证ID，全局唯一
 * @param bib             停留选手参赛号，必须与处罚对象一致
 * @param penaltyId       绑定的加时处罚ID
 * @param startMs         停留开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param endMs           停留结束时刻，Unix毫秒时间戳（UTC），区间右开
 * @param judge           登记裁判标识
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record AddExecutionRequest(
        @NotBlank String executionId,
        @NotBlank String bib,
        @NotBlank String penaltyId,
        @NotNull Long startMs,
        @NotNull Long endMs,
        @NotBlank String judge,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}