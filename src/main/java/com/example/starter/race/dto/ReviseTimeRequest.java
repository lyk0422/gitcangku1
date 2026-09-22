package com.example.starter.race.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 计时修订请求。
 *
 * @param requestId       全局唯一请求ID
 * @param rawTimeMs       修正后的原始完赛耗时（毫秒，1~86400000）
 * @param expectedVersion 期望的赛事当前版本，不一致返回409
 */
public record ReviseTimeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) @Max(86400000) Long rawTimeMs,
        @NotNull Long expectedVersion) {
}
