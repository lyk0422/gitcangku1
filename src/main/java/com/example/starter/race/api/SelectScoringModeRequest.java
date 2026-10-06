package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选择计分模式请求；仅 OPEN 且首次计时前可选择 HANDICAP，选择后不可更改。
 *
 * @param mode            计分模式，目前仅支持 "HANDICAP"（新建赛事默认为 STANDARD）
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SelectScoringModeRequest(
        @NotBlank String mode,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
