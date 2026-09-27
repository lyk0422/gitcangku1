package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 单个锁定点解除请求（锁定点标识由路径给出）：隔离令生效后锁定点须逐一解除，
 * 每次解除必须填写原因。尚有锁定点未解除时结束隔离（整令解除）返回 422。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号（逐点解除不推进版本，仅作乐观并发校验）
 * @param operatorId       解除操作人
 * @param reason           本次解除原因（必填），随锁定点操作证据固化
 */
public record ReleaseLockPointRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotBlank @Size(max = 512) String reason) {
}
