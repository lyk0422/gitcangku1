package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 登记设备请求。计量单位（MINUTES/HOURS）与保养周期登记后不可修改。
 * 单位缺省为 MINUTES；MINUTES 设备使用 maintenancePeriodMinutes（正整数分钟），
 * HOURS 设备使用 maintenancePeriod（十进制小时，最多 2 位小数）。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数；unit 缺省或 MINUTES 时必填
 * @param unit                      计量单位（MINUTES/HOURS），缺省 MINUTES，登记后不可更改
 * @param maintenancePeriod         保养周期（按声明单位，十进制最多 2 位小数）；unit 为 HOURS 时必填
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @Positive Long maintenancePeriodMinutes,
        String unit,
        @PositiveOrZero BigDecimal maintenancePeriod) {
}
