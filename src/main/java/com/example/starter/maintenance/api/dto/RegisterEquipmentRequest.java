package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备请求。保养周期登记后不可修改。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes) {
}
