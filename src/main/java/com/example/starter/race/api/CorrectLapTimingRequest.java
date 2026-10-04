package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 更正绕圈赛事圈次点位读数请求：必须引用最近有效（当前）记录ID，
 * 原读数追加到历史，不被覆盖。
 *
 * @param recordId        被更正的当前记录ID；已被更正过的旧ID返回409
 * @param newTimingId     更正后新当前记录的全局唯一ID
 * @param elapsedMillis   更正后的累计耗时（毫秒，非负整数）
 * @param expectedVersion 客户端所见赛事版本
 * @param operator        操作者标识（可选，纳入幂等身份）
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record CorrectLapTimingRequest(
        @NotBlank String recordId,
        @NotBlank String newTimingId,
        @NotNull @PositiveOrZero Long elapsedMillis,
        @NotNull Integer expectedVersion,
        String operator,
        @NotBlank String requestId
) {
}