package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 撤销当前生效晋级名单请求；撤销后原快照保留，可重新生成。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RevokeAdvancementRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
