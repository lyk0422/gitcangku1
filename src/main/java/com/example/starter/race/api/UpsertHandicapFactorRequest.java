package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记或修改选手让分系数请求；仅 OPEN 的 HANDICAP 赛事可写，成功后赛事版本加一。
 * 等比例系数（如 2/6 与 1/3）按约分后的值视为同参。
 *
 * @param p               让分系数分子，正整数 1~1000
 * @param q               让分系数分母，正整数 1~1000
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record UpsertHandicapFactorRequest(
        @NotNull @Min(1) @Max(1000) Integer p,
        @NotNull @Min(1) @Max(1000) Integer q,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
