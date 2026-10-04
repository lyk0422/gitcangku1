package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 更正绕圈赛事圈次点读数请求：必须引用最近有效记录ID，
 * 原读数追加到更正历史，不被覆盖。
 *
 * @param referenceLapPassId 被更正的最近有效记录ID（当前记录ID）
 * @param lapPassId          更正后新当前记录的全局唯一ID（第二层幂等键）
 * @param elapsedMillis      更正后的累计耗时（毫秒，非负整数，0~86400000）
 * @param operator           操作者标识（参与幂等身份计算）
 * @param expectedVersion    客户端所见赛事版本
 * @param requestId          全局唯一请求ID（写操作幂等键）
 */
public record CorrectLapRequest(
        @NotBlank String referenceLapPassId,
        @NotBlank String lapPassId,
        @NotNull @PositiveOrZero @Max(86_400_000L) Long elapsedMillis,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}