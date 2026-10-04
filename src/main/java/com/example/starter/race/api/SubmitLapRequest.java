package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 提交绕圈赛事选手某圈某点通过记录请求。
 *
 * @param lapPassId       全局唯一圈次记录ID（第二层幂等键）
 * @param lapNo           圈号（1~赛事总圈数），越界返回400
 * @param point           圈内点：A / B / FINISH
 * @param elapsedMillis   自起跑开始的累计耗时（毫秒，非负整数，0~86400000）
 * @param operator        操作者标识（参与幂等身份计算）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitLapRequest(
        @NotBlank String lapPassId,
        @NotNull Integer lapNo,
        @NotBlank String point,
        @NotNull @PositiveOrZero @Max(86_400_000L) Long elapsedMillis,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}