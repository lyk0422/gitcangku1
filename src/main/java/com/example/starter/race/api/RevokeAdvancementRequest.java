package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 整份撤销生效晋级名单请求：原快照保留为 REVOKED，之后可重新生成；封榜后禁止撤销。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RevokeAdvancementRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
