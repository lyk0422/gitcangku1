package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求；退赛后不参与排名、不占组别容量，且不可再提交或被批准重分。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
