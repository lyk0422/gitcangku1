package com.example.starter.maintenance.domain;

import java.math.BigDecimal;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param measurementUnit           登记计量单位（MINUTES/HOURS），登记后不可更改
 * @param maintenancePeriodValue    保养周期（登记单位，十进制最多 2 位小数），登记后不可修改
 * @param maintenancePeriodMinutes  保养周期换算分钟数（四舍五入到最近整数），判定统一口径
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(
        String equipmentId,
        MeasurementUnit measurementUnit,
        BigDecimal maintenancePeriodValue,
        long maintenancePeriodMinutes,
        long version) {
}
