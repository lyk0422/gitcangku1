package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认罚时执行凭证请求；确认裁判必须与登记裁判不同。
 *
 * @param judge           确认裁判标识（操作者）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfirmExecutionRequest(
        @NotBlank String judge,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}