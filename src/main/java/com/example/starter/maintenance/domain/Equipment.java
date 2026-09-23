package com.example.starter.maintenance.domain;

/**
 * 设备。
 *
 * @param equipmentId                 设备唯一标识
 * @param maintenancePeriodMinutes    保养周期（分钟），登记后不可修改
 * @param version                     设备版本号，初始 1，每次写操作校验并加一
 * @param maintenanceSnapshotVersion  保养快照版本号，初始 0；每次漂移修正激活成功在同一事务内加一
 */
public record Equipment(String equipmentId, long maintenancePeriodMinutes, long version,
                        long maintenanceSnapshotVersion) {
}
