package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 登记设备请求。保养周期登记后不可修改；疲劳阈值与基准应力可选，
 * 未配置基准应力的设备不能写入应力循环，未配置疲劳阈值则疲劳门禁不产生 DUE。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数
 * @param fatigueThreshold          疲劳阈值（正整数），本轮累计损伤达到即 DUE；可空
 * @param baselineStressMpa         基准应力（兆帕），非负整数；可空
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes,
        @Positive Long fatigueThreshold,
        @PositiveOrZero Long baselineStressMpa) {
}
