package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 阶段分段计时更正请求：更正后重算全部阶段最快值F与资格，不沿用旧最快值。
 *
 * @param requestId       全局唯一请求ID（写操作幂等键）
 * @param operator        非空操作者标识，参与幂等身份摘要
 * @param elapsedMillis   更正后的阶段内累计耗时（毫秒，1~86400000）
 * @param expectedVersion 客户端所见赛事版本
 */
public record ReviseStageTimingRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull @Positive @Max(86_400_000L) Long elapsedMillis,
        @NotNull Integer expectedVersion
) {
}
