package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;

/**
 * 设备视图（含单位配置）。
 *
 * @param equipmentId               设备唯一标识
 * @param unit                      计量单位（MINUTES/HOURS），登记后不可更改
 * @param maintenancePeriod         保养周期（按登记单位，十进制最多 2 位小数）
 * @param maintenancePeriodMinutes  保养周期（换算后分钟数），判定唯一口径
 * @param version                   设备当前版本号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        String unit,
        BigDecimal maintenancePeriod,
        long maintenancePeriodMinutes,
        long version) {
}
