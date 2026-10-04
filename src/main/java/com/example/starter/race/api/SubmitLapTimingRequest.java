package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 提交绕圈赛事选手某圈某点位通过记录请求。
 *
 * @param timingId        全局唯一圈次记录ID（第二层幂等键）
 * @param lapNumber       圈号，1~赛事总圈数；越界返回400
 * @param point           圈内点名：A / B / FINISH
 * @param elapsedMillis   自起跑开始的累计耗时（毫秒，非负整数）
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识（可选，纳入幂等身份）
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record SubmitLapTimingRequest(
        @NotBlank String timingId,
        @NotNull Integer lapNumber,
        @NotBlank String point,
        @NotNull @PositiveOrZero Long elapsedMillis,
        @NotNull Integer expectedVersion,
        String operator,
        @NotBlank String requestId
) {
}