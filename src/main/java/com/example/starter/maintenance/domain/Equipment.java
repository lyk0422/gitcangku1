package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 * @param commissionedAt            日历档案投用时刻（UTC）；null 表示未启用日历周期，启用后不可修改
 * @param calendarPeriodDays        日历保养周期（天），1 至 365 整数；null 表示未启用日历周期
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes, long version,
                        Instant commissionedAt, Integer calendarPeriodDays) {

    /** 是否已启用日历周期档案。 */
    public boolean calendarEnabled() {
        return calendarPeriodDays != null;
    }
}
