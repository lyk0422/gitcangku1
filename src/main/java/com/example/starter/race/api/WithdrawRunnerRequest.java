package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求；退赛为终态，释放其器材绑定。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record WithdrawRunnerRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
