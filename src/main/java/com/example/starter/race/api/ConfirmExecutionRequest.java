package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认执行凭证请求：由另一名裁判确认后，按该处罚尚未执行的毫秒数产生抵扣。
 *
 * @param confirmedBy     确认裁判，必须与登记裁判不同
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfirmExecutionRequest(
        @NotBlank String confirmedBy,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}