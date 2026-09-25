package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤销退赛请求；路径中的 withdrawalKey 定位退赛登记。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record RevokeWithdrawalRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
