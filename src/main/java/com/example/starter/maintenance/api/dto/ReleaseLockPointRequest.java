package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 单个锁定点解除请求：锁定点须逐一解除，每次解除必须填写原因。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param operatorId       解除操作人
 * @param reason           解除原因，不可为空白
 */
public record ReleaseLockPointRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotBlank String reason) {
}
