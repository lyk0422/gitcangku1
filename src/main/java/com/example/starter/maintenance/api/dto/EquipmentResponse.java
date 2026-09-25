package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备当前版本号，初始 1
 * @param retired                   是否已退役；退役设备的读数不可认证
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        long version,
        boolean retired) {
}
