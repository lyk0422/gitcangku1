package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param measurementUnit           登记计量单位（MINUTES/HOURS），登记后不可更改
 * @param maintenancePeriodValue    保养周期（登记单位十进制），登记后不可修改
 * @param maintenancePeriodMinutes  保养周期换算分钟数（判定统一口径）
 * @param version                   设备当前版本号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        String measurementUnit,
        BigDecimal maintenancePeriodValue,
        long maintenancePeriodMinutes,
        long version) {
}
