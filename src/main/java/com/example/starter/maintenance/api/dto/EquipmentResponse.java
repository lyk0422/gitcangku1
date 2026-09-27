package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param overdueThresholdMinutes   逾期阈值（分钟），可空；null 表示不判定 OVERDUE
 * @param version                   设备当前版本号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        Long overdueThresholdMinutes,
        long version) {
}
