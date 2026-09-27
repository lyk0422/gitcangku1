package com.example.starter.maintenance.api.dto;

/**
 * 设备视图。
 *
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），登记后不可修改
 * @param version                   设备当前版本号，初始 1
 * @param templateId                当前保养模板标识；null 表示未分配模板
 * @param currentCycleNo            当前保养周期序号，初始 1
 */
public record EquipmentResponse(
        String equipmentId,
        long maintenancePeriodMinutes,
        long version,
        String templateId,
        long currentCycleNo) {
}
