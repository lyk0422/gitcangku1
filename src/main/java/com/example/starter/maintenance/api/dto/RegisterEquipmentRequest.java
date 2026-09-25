package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备请求。保养周期与计量单位登记后不可修改。
 * 保养周期可用 maintenancePeriodValue（登记单位十进制，最多 2 位小数）或
 * maintenancePeriodMinutes（分钟整数，兼容旧调用）提供，二者至少其一；
 * 同时提供时以 maintenancePeriodValue 为准。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param measurementUnit           登记计量单位：MINUTES 或 HOURS；缺省为 MINUTES，登记后不可更改
 * @param maintenancePeriodValue    保养周期（登记单位十进制，最多 2 位小数），正数
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数（兼容字段）
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @Pattern(regexp = "MINUTES|HOURS", message = "must be MINUTES or HOURS") String measurementUnit,
        @Positive BigDecimal maintenancePeriodValue,
        @Positive Long maintenancePeriodMinutes) {
}
