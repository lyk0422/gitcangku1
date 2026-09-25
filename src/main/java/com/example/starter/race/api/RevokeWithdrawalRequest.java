package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤销退赛请求：必须携带登记时同一 withdrawalKey 与当前赛事版本。
 * 撤销后选手回到 UNTIMED 或 MISSING_CHECKPOINT；已撤销的退赛不能再次撤销。
 *
 * @param withdrawalKey   登记退赛时使用的全局唯一退赛键
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record RevokeWithdrawalRequest(
        @NotBlank String withdrawalKey,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
