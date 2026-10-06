package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param rangeM                    工时表量程 M（分钟）；null 表示未启用量程
 * @param version                   设备当前版本号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        Long rangeM,
        long version) {
}
