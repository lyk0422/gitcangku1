package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 设备停用请求：停用后不得再创建隔离令或提交读数/保养（返回 422）；
 * 存在未结束隔离令时不允许停用（返回 422）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致返回 409
 * @param operatorId       停用操作人
 */
public record DeactivateEquipmentRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId) {
}
