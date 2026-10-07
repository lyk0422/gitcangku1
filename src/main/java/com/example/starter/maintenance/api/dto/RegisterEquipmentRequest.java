package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 登记设备请求。保养周期、疲劳阈值、基准应力登记后不可修改。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数
 * @param fatigueThreshold          疲劳阈值（损伤累计达到即 DUE），正整数，可不设置
 * @param baselineStressMpa         基准应力（兆帕），非负整数，可不设置；未设置时不可登记应力循环
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes,
        @Positive Long fatigueThreshold,
        @PositiveOrZero Long baselineStressMpa) {
}
