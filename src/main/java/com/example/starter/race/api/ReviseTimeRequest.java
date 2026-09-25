package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 计时修订请求，携带 expectedVersion。
 *
 * @param bib             参赛号
 * @param finishTimeMs    修订后的原始完赛耗时（毫秒，1~86400000）；
 *                        null 表示清除完赛计时，选手回到 UNTIMED，既有分段记录保留
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ReviseTimeRequest(
        @NotBlank String bib,
        Long finishTimeMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
