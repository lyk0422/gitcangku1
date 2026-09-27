package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 停用设备请求：已存在未结束隔离令（PENDING/ISOLATED）的设备返回 422。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param operator         停用操作者（固化留痕）
 */
public record DecommissionEquipmentRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operator) {
}
