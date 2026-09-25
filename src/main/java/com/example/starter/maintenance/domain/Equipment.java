package com.example.starter.maintenance.domain;

import java.math.BigDecimal;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param unit                      计量单位（MINUTES/HOURS），登记后不可更改
 * @param maintenancePeriodValue    保养周期（按登记单位，十进制最多 2 位小数）
 * @param maintenancePeriodMinutes  保养周期（换算后分钟数），判定唯一口径
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 */
public record Equipment(
        String equipmentId,
        MeterUnit unit,
        BigDecimal maintenancePeriodValue,
        long maintenancePeriodMinutes,
        long version) {
}
