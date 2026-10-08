package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 启用区间计时模式请求；仅尚无任何计时证据的 OPEN 赛事可启用，启用后不可回退。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识，非空
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record EnableIntervalModeRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String operator,
        @NotBlank String requestId
) {
}
