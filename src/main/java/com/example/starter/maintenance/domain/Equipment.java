package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 设备。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备版本号，初始 1，每次写操作校验并加一
 * @param retired                   是否已退役；退役设备的读数不可认证
 * @param retiredAt                 退役时刻（UTC），未退役为 null
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes, long version,
                        boolean retired, Instant retiredAt) {
}
