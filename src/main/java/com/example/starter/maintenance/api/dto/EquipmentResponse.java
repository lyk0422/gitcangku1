package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备当前版本号，初始 1
 * @param status                    设备状态：ACTIVE 在用 / DECOMMISSIONED 已停用
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        long version,
        String status) {
}
