package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 * @param retiredAt                 退役时刻（UTC）；null 表示在役，退役后不可再认证读数
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes, long version,
                        Instant retiredAt) {
}
