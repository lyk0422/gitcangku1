package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备请求。保养周期登记后不可修改；逾期阈值可空，非空时须严格大于保养周期。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数；本轮运行分钟达到该值为 DUE
 * @param overdueThresholdMinutes   逾期阈值（分钟），可空；非空时达到该值为 OVERDUE，null 表示不判定 OVERDUE
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes,
        @Positive Long overdueThresholdMinutes) {
}
