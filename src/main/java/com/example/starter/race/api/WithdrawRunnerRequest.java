package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 选手退赛请求；退赛为终态并释放其器材绑定。
 *
 * @param reason          退赛原因，可空，最长256字符
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @Size(max = 256) String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
