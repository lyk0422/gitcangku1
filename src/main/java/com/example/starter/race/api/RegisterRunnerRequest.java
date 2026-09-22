package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记选手请求；不传 finishTimeMs 时该选手计时缺失（UNTIMED）。
 *
 * @param bib             参赛号，赛事内唯一
 * @param finishTimeMs    原始完赛耗时（毫秒，1~86400000）；可空表示暂未计时
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RegisterRunnerRequest(
        @NotBlank String bib,
        @Positive Long finishTimeMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
